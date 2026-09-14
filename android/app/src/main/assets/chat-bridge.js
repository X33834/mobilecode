#!/usr/bin/env node
/**
 * Mobilecode chat-bridge —— 本地协议桥（Responses API ⇄ Chat Completions）
 * ================================================================
 *
 * 背景：Codex CLI 0.104.0 起彻底移除了 wire_api = "chat"，引擎只会说
 * Responses API（POST {base_url}/responses + SSE）。而 DeepSeek / 通义千问 /
 * 智谱 GLM 等国内 provider 只提供 Chat Completions 接口。
 *
 * 本桥在设备本地（127.0.0.1:18925）把引擎的 Responses 请求实时翻译成
 * Chat Completions，再把上游的 chat SSE 流翻译回 Responses SSE 事件流：
 *
 *   codex 引擎 ──POST http://127.0.0.1:18925/<prov>/v1/responses──▶ bridge
 *   bridge ──POST https://<上游>/v1/chat/completions──▶ provider
 *   bridge ◀──chat SSE（delta.content / delta.tool_calls）── provider
 *   codex 引擎 ◀──responses SSE（output_text.delta / function_call ...）── bridge
 *
 * 设计约束（与 proxy.js 一致）：
 * - 零 npm 依赖，纯 Node 标准库（http/https），镜像内直接可跑；
 * - 只监听 127.0.0.1，绝不对局域网暴露（v0.5.0 安全原则）；
 * - Authorization 头原样透传，Key 不落地、不打日志。
 *
 * 路由表（路径前缀 → 上游 chat-completions 风格 base）：
 *   /deepseek → https://api.deepseek.com/v1
 *   /qwen     → https://dashscope.aliyuncs.com/compatible-mode/v1
 *   /glm      → https://open.bigmodel.cn/api/paas/v4
 * 测试时可聊 CHAT_BRIDGE_ROUTES 环境变量覆盖（如指向本地 mock）。
 */
"use strict";

const http = require("http");
const https = require("https");
const { URL } = require("url");

const PORT = parseInt(process.env.CHAT_BRIDGE_PORT || "18925", 10);
const HOST = "127.0.0.1";
const UPSTREAM_TIMEOUT_MS = 300_000; // 长回答/长工具循环，5 分钟上限

const ROUTES = {
  "/deepseek": "https://api.deepseek.com/v1",
  "/qwen": "https://dashscope.aliyuncs.com/compatible-mode/v1",
  "/glm": "https://open.bigmodel.cn/api/paas/v4",
};
// 支持 stream_options.include_usage 的上游（GLM 兼容层不支持，避免 400）
const STREAM_OPTIONS_OK = new Set(["/deepseek", "/qwen"]);

if (process.env.CHAT_BRIDGE_ROUTES) {
  for (const pair of process.env.CHAT_BRIDGE_ROUTES.split(",")) {
    const eq = pair.indexOf("=");
    if (eq > 0) {
      const k = pair.slice(0, eq).trim();
      const v = pair.slice(eq + 1).trim();
      if (k && v) ROUTES[k] = v;
    }
  }
}

function log(...args) {
  console.log(new Date().toISOString(), "[chat-bridge]", ...args);
}

// ── Responses → Chat 请求转换 ────────────────────────────────────

function contentToText(content) {
  if (typeof content === "string") return content;
  if (Array.isArray(content)) {
    return content
      .map((c) => (typeof c === "string" ? c : (c && (c.text || c.content)) || ""))
      .join("");
  }
  return "";
}

function mapToolChoice(tc) {
  if (!tc) return "auto";
  if (tc === "auto" || tc === "none" || tc === "required") return tc;
  if (typeof tc === "object" && tc.type === "function" && tc.name) {
    return { type: "function", function: { name: tc.name } };
  }
  return "auto";
}

function responsesToChat(body, providerPath) {
  const messages = [];
  if (body.instructions) {
    messages.push({ role: "system", content: String(body.instructions) });
  }
  const input = Array.isArray(body.input) ? body.input : [{ type: "message", role: "user", content: body.input }];

  for (const item of input) {
    if (typeof item === "string") {
      messages.push({ role: "user", content: item });
      continue;
    }
    switch (item.type) {
      case "message": {
        const role = item.role === "developer" ? "system" : item.role || "user";
        if (role === "assistant" && messages.length && messages[messages.length - 1].role === "assistant" && !messages[messages.length - 1].tool_calls) {
          messages[messages.length - 1].content += "\n" + contentToText(item.content);
        } else {
          messages.push({ role, content: contentToText(item.content) });
        }
        break;
      }
      case "function_call": {
        const call = {
          id: item.call_id || item.id || "call_0",
          type: "function",
          function: { name: item.name, arguments: item.arguments || "{}" },
        };
        const last = messages[messages.length - 1];
        if (last && last.role === "assistant" && Array.isArray(last.tool_calls)) {
          last.tool_calls.push(call);
        } else {
          messages.push({ role: "assistant", content: null, tool_calls: [call] });
        }
        break;
      }
      case "function_call_output": {
        let out = item.output;
        if (typeof out !== "string") out = JSON.stringify(out);
        messages.push({ role: "tool", tool_call_id: item.call_id || "call_0", content: out });
        break;
      }
      default:
        break; // reasoning / local_shell_call / 其他内部类型：chat 上游不消费
    }
  }

  const tools = [];
  for (const t of body.tools || []) {
    if (t && t.type === "function" && t.name) {
      tools.push({
        type: "function",
        function: {
          name: t.name,
          description: t.description || "",
          parameters: t.parameters || { type: "object", properties: {} },
        },
      });
    }
  }

  const out = { model: body.model, messages, stream: true };
  if (tools.length) {
    out.tools = tools;
    out.tool_choice = mapToolChoice(body.tool_choice);
    if (body.parallel_tool_calls !== undefined) out.parallel_tool_calls = !!body.parallel_tool_calls;
  }
  if (STREAM_OPTIONS_OK.has(providerPath)) out.stream_options = { include_usage: true };
  return out;
}

// ── Chat SSE → Responses SSE 响应转换 ────────────────────────────

function sseSend(res, type, payload) {
  const obj = Object.assign({}, payload, { type });
  res.write(`event: ${type}\ndata: ${JSON.stringify(obj)}\n\n`);
}

function msgItem(id, text) {
  return {
    type: "message",
    id,
    role: "assistant",
    status: "completed",
    content: [{ type: "output_text", text }],
  };
}

function fcItem(id, callId, name, args) {
  return { type: "function_call", id, call_id: callId, name, arguments: args };
}

/**
 * 把 chat 上游的响应流翻译成 Responses SSE 写给引擎。
 * 支持：上游 SSE（主路径）与上游一次性 JSON（兼容层不带流时）。
 */
function translateUpstream(upStream, upStatus, upHeaders, engineRes, respId, onFinish) {
  const isSSE = String(upHeaders["content-type"] || "").includes("text/event-stream");

  if (!isSSE) {
    // 非流式：缓冲整个 JSON 再合成完整事件序列
    const chunks = [];
    upStream.on("data", (c) => chunks.push(c));
    upStream.on("end", () => {
      const raw = Buffer.concat(chunks).toString("utf8");
      if (upStatus !== 200) {
        engineRes.writeHead(upStatus, { "Content-Type": "application/json" });
        engineRes.end(raw);
        onFinish && onFinish();
        return;
      }
      let body = {};
      try { body = JSON.parse(raw); } catch { body = {}; }
      const choice = (body.choices && body.choices[0]) || {};
      const m = choice.message || {};
      const output = [];
      engineRes.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache", "Connection": "close" });
      sseSend(engineRes, "response.created", { response: { id: respId } });
      let idx = 0;
      if (m.content) {
        const item = msgItem("msg-bridge", String(m.content));
        engineRes.write; // noop 保持可读性
        sseSend(engineRes, "response.output_item.added", { output_index: idx, item: { type: "message", id: item.id, role: "assistant", content: [{ type: "output_text", text: "" }] } });
        sseSend(engineRes, "response.output_text.delta", { item_id: item.id, output_index: idx, content_index: 0, delta: item.content[0].text });
        sseSend(engineRes, "response.output_text.done", { item_id: item.id, output_index: idx, content_index: 0, text: item.content[0].text });
        sseSend(engineRes, "response.output_item.done", { output_index: idx, item });
        output.push(item);
        idx++;
      }
      for (const tc of m.tool_calls || []) {
        const item = fcItem("fc-bridge-" + tc.id, tc.id, tc.function.name, tc.function.arguments || "{}");
        sseSend(engineRes, "response.output_item.done", { output_index: idx, item });
        output.push(item);
        idx++;
      }
      sseSend(engineRes, "response.completed", {
        response: {
          id: respId, status: "completed", output,
          usage: body.usage ? {
            input_tokens: body.usage.prompt_tokens || 0,
            output_tokens: body.usage.completion_tokens || 0,
            total_tokens: body.usage.total_tokens || 0,
          } : undefined,
        },
      });
      engineRes.end();
      onFinish && onFinish();
    });
    upStream.on("error", (e) => { try { engineRes.destroy(e); } catch {} });
    return;
  }

  // 流式：状态机逐块翻译
  engineRes.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache", "Connection": "close" });
  sseSend(engineRes, "response.created", { response: { id: respId } });

  let itemIndex = 0;
  let textOpen = false;
  let textAccum = ""; // 累计本轮 assistant 文本（output_item.done 必须带全文，引擎不以 delta 累积）
  const tools = new Map(); // chat tool index -> {callId, name, args}
  const output = [];
  let usage;

  const closeText = () => {
    if (!textOpen) return;
    textOpen = false;
    // text.done / item.done 在 [DONE] 汇总阶段统一发出（保持事件顺序简单）
  };

  const flushToolCalls = () => {
    for (const [, tc] of [...tools.entries()].sort((a, b) => a[0] - b[0])) {
      const item = fcItem("fc-bridge-" + tc.callId, tc.callId, tc.name, tc.args || "{}");
      sseSend(engineRes, "response.output_item.added", { output_index: itemIndex, item });
      sseSend(engineRes, "response.function_call_arguments.done", { item_id: item.id, output_index: itemIndex, arguments: item.arguments });
      sseSend(engineRes, "response.output_item.done", { output_index: itemIndex, item });
      output.push(item);
      itemIndex++;
    }
    tools.clear();
  };

  let buf = "";
  let finished = false;

  const finalizeStream = () => {
    if (finished) return;
    finished = true;
    if (textOpen) {
      const id = engineRes._textId;
      // 关键：codex 用 output_item.done / output_text.done 里的全文构建最终消息，
      // 不从 delta 累积 —— done 事件必须携带完整累计文本
      sseSend(engineRes, "response.output_text.done", { item_id: id, output_index: itemIndex, content_index: 0, text: textAccum });
      sseSend(engineRes, "response.output_item.done", {
        output_index: itemIndex,
        item: msgItem(id, textAccum),
      });
      output.push(msgItem(id, textAccum));
      itemIndex++;
      textOpen = false;
    }
    flushToolCalls();
    sseSend(engineRes, "response.completed", {
      response: { id: respId, status: "completed", output, usage },
    });
    engineRes.end();
    onFinish && onFinish();
    try { upStream.destroy(); } catch {}
  };

  upStream.on("data", (c) => {
    if (finished) return;
    buf += c.toString("utf8");
    let nl;
    while ((nl = buf.indexOf("\n")) >= 0) {
      const line = buf.slice(0, nl).replace(/\r$/, "");
      buf = buf.slice(nl + 1);
      if (!line.startsWith("data:")) continue;
      const payload = line.slice(5).trim();
      if (payload === "[DONE]") {
        // chat SSE 的终止标志 —— 真实上游 keep-alive 不会关连接，必须就地收尾
        finalizeStream();
        return;
      }
      if (!payload) continue;
      let chunk;
      try { chunk = JSON.parse(payload); } catch { continue; }

      if (chunk.usage) {
        usage = {
          input_tokens: chunk.usage.prompt_tokens || 0,
          output_tokens: chunk.usage.completion_tokens || 0,
          total_tokens: chunk.usage.total_tokens || 0,
        };
      }
      const choice = (chunk.choices && chunk.choices[0]) || {};
      const delta = choice.delta || {};

      if (typeof delta.content === "string" && delta.content.length) {
        if (!textOpen) {
          textOpen = true;
          textAccum = "";
          const id = "msg-bridge-" + itemIndex;
          sseSend(engineRes, "response.output_item.added", {
            output_index: itemIndex,
            item: { type: "message", id, role: "assistant", content: [{ type: "output_text", text: "" }] },
          });
          engineRes._textId = id;
        }
        textAccum += delta.content;
        sseSend(engineRes, "response.output_text.delta", {
          item_id: engineRes._textId, output_index: itemIndex, content_index: 0, delta: delta.content,
        });
      }

      for (const tc of delta.tool_calls || []) {
        const cur = tools.get(tc.index || 0) || { callId: tc.id || "call_" + (tc.index || 0), name: "", args: "" };
        if (tc.id) cur.callId = tc.id;
        if (tc.function) {
          if (tc.function.name) cur.name += tc.function.name;
          if (tc.function.arguments) cur.args += tc.function.arguments;
        }
        tools.set(tc.index || 0, cur);
      }

      if (choice.finish_reason === "tool_calls" || choice.finish_reason === "function_call") {
        closeText();
        flushToolCalls();
      }
    }
  });

  upStream.on("end", () => finalizeStream());
  upStream.on("error", (e) => {
    log("upstream stream error:", e.message);
    try { engineRes.destroy(e); } catch {}
  });
}

// ── 上游请求 ─────────────────────────────────────────────────────

function requestUpstream(urlStr, headers, bodyBuf, isHttps, cb) {
  const u = new URL(urlStr);
  const mod = isHttps ? https : http;
  const req = mod.request(
    {
      hostname: u.hostname,
      port: u.port || (isHttps ? 443 : 80),
      path: u.pathname + u.search,
      method: "POST",
      headers,
    },
    cb,
  );
  req.setTimeout(UPSTREAM_TIMEOUT_MS, () => req.destroy(new Error("upstream timeout")));
  req.on("error", (e) => log("upstream error:", e.message));
  if (bodyBuf) req.write(bodyBuf);
  req.end();
  return req;
}

// ── 服务器 ───────────────────────────────────────────────────────

const server = http.createServer((engineReq, engineRes) => {
  const url = engineReq.url || "/";
  const prefix = Object.keys(ROUTES).find(
    (p) => url === p || url.startsWith(p + "/"),
  );
  if (!prefix) {
    engineRes.writeHead(404, { "Content-Type": "application/json" });
    engineRes.end(JSON.stringify({ error: { message: `chat-bridge: unknown route ${url}` } }));
    return;
  }
  const upstreamBase = ROUTES[prefix];
  // 引擎侧 base_url 形如 http://127.0.0.1:18925/deepseek/v1（sub = /v1/responses），
  // 上游 base 已含版本段 → 剥掉 sub 的版本前缀，只保留动作段（/responses 等）
  const sub = (url.slice(prefix.length) || "/").replace(/^\/(v1\/)?/, "/");
  const isHttps = upstreamBase.startsWith("https:");
  const upstream = new URL(upstreamBase);

  // 通用转发头；Authorization（provider Key）原样透传
  const fwdHeaders = {
    host: upstream.host,
    "content-type": "application/json",
    accept: engineReq.headers.accept || "application/json",
  };
  if (engineReq.headers.authorization) fwdHeaders.authorization = engineReq.headers.authorization;

  const readBody = () =>
    new Promise((resolve) => {
      const chunks = [];
      engineReq.on("data", (c) => chunks.push(c));
      engineReq.on("end", () => resolve(Buffer.concat(chunks)));
    });

  (async () => {
    const bodyBuf = await readBody();

    // POST …/responses：协议转换主路径
    if (engineReq.method === "POST" && /\/responses$/.test(sub)) {
      let inBody = {};
      try { inBody = JSON.parse(bodyBuf.toString("utf8") || "{}"); } catch {}
      const chatBody = responsesToChat(inBody, prefix);
      log("chat body roles:", chatBody.messages.map((m) => m.role + (m.tool_calls ? `(+${m.tool_calls.length} calls)` : "")).join(","));
      const out = Buffer.from(JSON.stringify(chatBody));
      fwdHeaders["content-length"] = out.length;
      const chatUrl = upstreamBase + sub.replace(/\/responses$/, "/chat/completions");
      const respId = "resp-bridge-" + Date.now().toString(36);
      log(`${prefix} ${inBody.model || "?"} stream → ${chatUrl}`);
      requestUpstream(
        chatUrl,
        fwdHeaders,
        out,
        isHttps,
        (upRes) => {
          if (upRes.statusCode !== 200) {
            // 错误体透传给引擎（引擎会展示给用户）
            const chunks = [];
            upRes.on("data", (c) => chunks.push(c));
            upRes.on("end", () => {
              engineRes.writeHead(upRes.statusCode, { "Content-Type": upRes.headers["content-type"] || "application/json" });
              engineRes.end(Buffer.concat(chunks));
              log(`${prefix} upstream ${upRes.statusCode}: ${Buffer.concat(chunks).toString("utf8").slice(0, 300)}`);
            });
            return;
          }
          translateUpstream(upRes, upRes.statusCode, upRes.headers, engineRes, respId, () =>
            log(`${prefix} done (${respId})`),
          );
        },
      );
      return;
    }

    // 其他路径（GET /models 等）：直接透传
    const passUrl = upstreamBase + sub;
    const u = new URL(passUrl);
    const mod = isHttps ? https : http;
    const preq = mod.request(
      {
        hostname: u.hostname,
        port: u.port || (isHttps ? 443 : 80),
        path: u.pathname + u.search,
        method: engineReq.method,
        headers: fwdHeaders,
      },
      (pres) => {
        engineRes.writeHead(pres.statusCode || 502, pres.headers);
        pres.pipe(engineRes);
      },
    );
    preq.setTimeout(60_000, () => preq.destroy(new Error("upstream timeout")));
    preq.on("error", (e) => {
      try {
        engineRes.writeHead(502, { "Content-Type": "application/json" });
        engineRes.end(JSON.stringify({ error: { message: "chat-bridge: " + e.message } }));
      } catch {}
    });
    if (bodyBuf && engineReq.method !== "GET" && engineReq.method !== "HEAD") preq.write(bodyBuf);
    preq.end();
  })().catch((e) => {
    log("handler error:", e.stack || e.message);
    try {
      engineRes.writeHead(500, { "Content-Type": "application/json" });
      engineRes.end(JSON.stringify({ error: { message: "chat-bridge: " + e.message } }));
    } catch {}
  });
});

server.listen(PORT, HOST, () => {
  log(`chat-bridge listening on http://${HOST}:${PORT}`);
  for (const [p, up] of Object.entries(ROUTES)) log(`  ${p}/* → ${up}`);
});

process.on("SIGTERM", () => process.exit(0));
process.on("SIGINT", () => process.exit(0));
