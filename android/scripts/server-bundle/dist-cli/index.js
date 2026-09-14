#!/usr/bin/env node

// src/cli/index.ts
import { createServer as createServer2 } from "http";
import { Command } from "commander";

// src/server/httpServer.ts
import { fileURLToPath } from "url";
import { dirname, join as join2 } from "path";
import express from "express";

// src/server/codexAppServerBridge.ts
import { spawn } from "child_process";
import { mkdtemp, readFile } from "fs/promises";
import { tmpdir } from "os";
import { join } from "path";
function asRecord(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value : null;
}
function getErrorMessage(payload, fallback) {
  if (payload instanceof Error && payload.message.trim().length > 0) {
    return payload.message;
  }
  const record = asRecord(payload);
  if (!record) return fallback;
  const error = record.error;
  if (typeof error === "string" && error.length > 0) return error;
  const nestedError = asRecord(error);
  if (nestedError && typeof nestedError.message === "string" && nestedError.message.length > 0) {
    return nestedError.message;
  }
  return fallback;
}
function setJson(res, statusCode, payload) {
  res.statusCode = statusCode;
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  res.end(JSON.stringify(payload));
}
async function readJsonBody(req) {
  const chunks = [];
  for await (const chunk of req) {
    chunks.push(typeof chunk === "string" ? Buffer.from(chunk) : chunk);
  }
  if (chunks.length === 0) return null;
  const raw = Buffer.concat(chunks).toString("utf8").trim();
  if (raw.length === 0) return null;
  return JSON.parse(raw);
}
var AppServerProcess = class {
  constructor() {
    this.process = null;
    this.initialized = false;
    this.readBuffer = "";
    this.nextId = 1;
    this.stopping = false;
    this.pending = /* @__PURE__ */ new Map();
    this.notificationListeners = /* @__PURE__ */ new Set();
    this.pendingServerRequests = /* @__PURE__ */ new Map();
  }
  start() {
    if (this.process) return;
    this.stopping = false;
    const proc = spawn("codex", ["app-server"], { stdio: ["pipe", "pipe", "pipe"] });
    this.process = proc;
    proc.stdout.setEncoding("utf8");
    proc.stdout.on("data", (chunk) => {
      this.readBuffer += chunk;
      let lineEnd = this.readBuffer.indexOf("\n");
      while (lineEnd !== -1) {
        const line = this.readBuffer.slice(0, lineEnd).trim();
        this.readBuffer = this.readBuffer.slice(lineEnd + 1);
        if (line.length > 0) {
          this.handleLine(line);
        }
        lineEnd = this.readBuffer.indexOf("\n");
      }
    });
    proc.stderr.setEncoding("utf8");
    proc.stderr.on("data", () => {
    });
    proc.on("exit", () => {
      const failure = new Error(this.stopping ? "codex app-server stopped" : "codex app-server exited unexpectedly");
      for (const request of this.pending.values()) {
        request.reject(failure);
      }
      this.pending.clear();
      this.pendingServerRequests.clear();
      this.process = null;
      this.initialized = false;
      this.readBuffer = "";
    });
  }
  sendLine(payload) {
    if (!this.process) {
      throw new Error("codex app-server is not running");
    }
    this.process.stdin.write(`${JSON.stringify(payload)}
`);
  }
  handleLine(line) {
    let message;
    try {
      message = JSON.parse(line);
    } catch {
      return;
    }
    if (typeof message.id === "number" && this.pending.has(message.id)) {
      const pendingRequest = this.pending.get(message.id);
      this.pending.delete(message.id);
      if (!pendingRequest) return;
      if (message.error) {
        pendingRequest.reject(new Error(message.error.message));
      } else {
        pendingRequest.resolve(message.result);
      }
      return;
    }
    if (typeof message.method === "string" && typeof message.id !== "number") {
      this.emitNotification({
        method: message.method,
        params: message.params ?? null
      });
      return;
    }
    if (typeof message.id === "number" && typeof message.method === "string") {
      this.handleServerRequest(message.id, message.method, message.params ?? null);
    }
  }
  emitNotification(notification) {
    for (const listener of this.notificationListeners) {
      listener(notification);
    }
  }
  sendServerRequestReply(requestId, reply) {
    if (reply.error) {
      this.sendLine({
        jsonrpc: "2.0",
        id: requestId,
        error: reply.error
      });
      return;
    }
    this.sendLine({
      jsonrpc: "2.0",
      id: requestId,
      result: reply.result ?? {}
    });
  }
  resolvePendingServerRequest(requestId, reply) {
    const pendingRequest = this.pendingServerRequests.get(requestId);
    if (!pendingRequest) {
      throw new Error(`No pending server request found for id ${String(requestId)}`);
    }
    this.pendingServerRequests.delete(requestId);
    this.sendServerRequestReply(requestId, reply);
    const requestParams = asRecord(pendingRequest.params);
    const threadId = typeof requestParams?.threadId === "string" && requestParams.threadId.length > 0 ? requestParams.threadId : "";
    this.emitNotification({
      method: "server/request/resolved",
      params: {
        id: requestId,
        method: pendingRequest.method,
        threadId,
        mode: "manual",
        resolvedAtIso: (/* @__PURE__ */ new Date()).toISOString()
      }
    });
  }
  handleServerRequest(requestId, method, params) {
    const pendingRequest = {
      id: requestId,
      method,
      params,
      receivedAtIso: (/* @__PURE__ */ new Date()).toISOString()
    };
    this.pendingServerRequests.set(requestId, pendingRequest);
    this.emitNotification({
      method: "server/request",
      params: pendingRequest
    });
  }
  async call(method, params) {
    this.start();
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.sendLine({
        jsonrpc: "2.0",
        id,
        method,
        params
      });
    });
  }
  async ensureInitialized() {
    if (this.initialized) return;
    await this.call("initialize", {
      clientInfo: {
        name: "codex-web-local",
        version: "0.1.0"
      }
    });
    this.initialized = true;
  }
  async rpc(method, params) {
    await this.ensureInitialized();
    return this.call(method, params);
  }
  onNotification(listener) {
    this.notificationListeners.add(listener);
    return () => {
      this.notificationListeners.delete(listener);
    };
  }
  async respondToServerRequest(payload) {
    await this.ensureInitialized();
    const body = asRecord(payload);
    if (!body) {
      throw new Error("Invalid response payload: expected object");
    }
    const id = body.id;
    if (typeof id !== "number" || !Number.isInteger(id)) {
      throw new Error('Invalid response payload: "id" must be an integer');
    }
    const rawError = asRecord(body.error);
    if (rawError) {
      const message = typeof rawError.message === "string" && rawError.message.trim().length > 0 ? rawError.message.trim() : "Server request rejected by client";
      const code = typeof rawError.code === "number" && Number.isFinite(rawError.code) ? Math.trunc(rawError.code) : -32e3;
      this.resolvePendingServerRequest(id, { error: { code, message } });
      return;
    }
    if (!("result" in body)) {
      throw new Error('Invalid response payload: expected "result" or "error"');
    }
    this.resolvePendingServerRequest(id, { result: body.result });
  }
  listPendingServerRequests() {
    return Array.from(this.pendingServerRequests.values());
  }
  dispose() {
    if (!this.process) return;
    const proc = this.process;
    this.stopping = true;
    this.process = null;
    this.initialized = false;
    this.readBuffer = "";
    const failure = new Error("codex app-server stopped");
    for (const request of this.pending.values()) {
      request.reject(failure);
    }
    this.pending.clear();
    this.pendingServerRequests.clear();
    try {
      proc.stdin.end();
    } catch {
    }
    try {
      proc.kill("SIGTERM");
    } catch {
    }
    const forceKillTimer = setTimeout(() => {
      if (!proc.killed) {
        try {
          proc.kill("SIGKILL");
        } catch {
        }
      }
    }, 1500);
    forceKillTimer.unref();
  }
};
var MethodCatalog = class {
  constructor() {
    this.methodCache = null;
    this.notificationCache = null;
  }
  async runGenerateSchemaCommand(outDir) {
    await new Promise((resolve, reject) => {
      const process2 = spawn("codex", ["app-server", "generate-json-schema", "--out", outDir], {
        stdio: ["ignore", "ignore", "pipe"]
      });
      let stderr = "";
      process2.stderr.setEncoding("utf8");
      process2.stderr.on("data", (chunk) => {
        stderr += chunk;
      });
      process2.on("error", reject);
      process2.on("exit", (code) => {
        if (code === 0) {
          resolve();
          return;
        }
        reject(new Error(stderr.trim() || `generate-json-schema exited with code ${String(code)}`));
      });
    });
  }
  extractMethodsFromClientRequest(payload) {
    const root = asRecord(payload);
    const oneOf = Array.isArray(root?.oneOf) ? root.oneOf : [];
    const methods = /* @__PURE__ */ new Set();
    for (const entry of oneOf) {
      const row = asRecord(entry);
      const properties = asRecord(row?.properties);
      const methodDef = asRecord(properties?.method);
      const methodEnum = Array.isArray(methodDef?.enum) ? methodDef.enum : [];
      for (const item of methodEnum) {
        if (typeof item === "string" && item.length > 0) {
          methods.add(item);
        }
      }
    }
    return Array.from(methods).sort((a, b) => a.localeCompare(b));
  }
  extractMethodsFromServerNotification(payload) {
    const root = asRecord(payload);
    const oneOf = Array.isArray(root?.oneOf) ? root.oneOf : [];
    const methods = /* @__PURE__ */ new Set();
    for (const entry of oneOf) {
      const row = asRecord(entry);
      const properties = asRecord(row?.properties);
      const methodDef = asRecord(properties?.method);
      const methodEnum = Array.isArray(methodDef?.enum) ? methodDef.enum : [];
      for (const item of methodEnum) {
        if (typeof item === "string" && item.length > 0) {
          methods.add(item);
        }
      }
    }
    return Array.from(methods).sort((a, b) => a.localeCompare(b));
  }
  async listMethods() {
    if (this.methodCache) {
      return this.methodCache;
    }
    const outDir = await mkdtemp(join(tmpdir(), "codex-web-local-schema-"));
    await this.runGenerateSchemaCommand(outDir);
    const clientRequestPath = join(outDir, "ClientRequest.json");
    const raw = await readFile(clientRequestPath, "utf8");
    const parsed = JSON.parse(raw);
    const methods = this.extractMethodsFromClientRequest(parsed);
    this.methodCache = methods;
    return methods;
  }
  async listNotificationMethods() {
    if (this.notificationCache) {
      return this.notificationCache;
    }
    const outDir = await mkdtemp(join(tmpdir(), "codex-web-local-schema-"));
    await this.runGenerateSchemaCommand(outDir);
    const serverNotificationPath = join(outDir, "ServerNotification.json");
    const raw = await readFile(serverNotificationPath, "utf8");
    const parsed = JSON.parse(raw);
    const methods = this.extractMethodsFromServerNotification(parsed);
    this.notificationCache = methods;
    return methods;
  }
};
var SHARED_BRIDGE_KEY = "__codexRemoteSharedBridge__";
function getSharedBridgeState() {
  const globalScope = globalThis;
  const existing = globalScope[SHARED_BRIDGE_KEY];
  if (existing) return existing;
  const created = {
    appServer: new AppServerProcess(),
    methodCatalog: new MethodCatalog()
  };
  globalScope[SHARED_BRIDGE_KEY] = created;
  return created;
}
function createCodexBridgeMiddleware() {
  const { appServer, methodCatalog } = getSharedBridgeState();
  const middleware = async (req, res, next) => {
    try {
      if (!req.url) {
        next();
        return;
      }
      const url = new URL(req.url, "http://localhost");
      if (req.method === "POST" && url.pathname === "/codex-api/rpc") {
        const payload = await readJsonBody(req);
        const body = asRecord(payload);
        if (!body || typeof body.method !== "string" || body.method.length === 0) {
          setJson(res, 400, { error: "Invalid body: expected { method, params? }" });
          return;
        }
        const result = await appServer.rpc(body.method, body.params ?? null);
        setJson(res, 200, { result });
        return;
      }
      if (req.method === "POST" && url.pathname === "/codex-api/server-requests/respond") {
        const payload = await readJsonBody(req);
        await appServer.respondToServerRequest(payload);
        setJson(res, 200, { ok: true });
        return;
      }
      if (req.method === "GET" && url.pathname === "/codex-api/server-requests/pending") {
        setJson(res, 200, { data: appServer.listPendingServerRequests() });
        return;
      }
      if (req.method === "GET" && url.pathname === "/codex-api/meta/methods") {
        const methods = await methodCatalog.listMethods();
        setJson(res, 200, { data: methods });
        return;
      }
      if (req.method === "GET" && url.pathname === "/codex-api/meta/notifications") {
        const methods = await methodCatalog.listNotificationMethods();
        setJson(res, 200, { data: methods });
        return;
      }
      if (req.method === "GET" && url.pathname === "/codex-api/events") {
        res.statusCode = 200;
        res.setHeader("Content-Type", "text/event-stream; charset=utf-8");
        res.setHeader("Cache-Control", "no-cache, no-transform");
        res.setHeader("Connection", "keep-alive");
        res.setHeader("X-Accel-Buffering", "no");
        const unsubscribe = appServer.onNotification((notification) => {
          if (res.writableEnded || res.destroyed) return;
          const payload = {
            ...notification,
            atIso: (/* @__PURE__ */ new Date()).toISOString()
          };
          res.write(`data: ${JSON.stringify(payload)}

`);
        });
        res.write(`event: ready
data: ${JSON.stringify({ ok: true })}

`);
        const keepAlive = setInterval(() => {
          res.write(": ping\n\n");
        }, 15e3);
        const close = () => {
          clearInterval(keepAlive);
          unsubscribe();
          if (!res.writableEnded) {
            res.end();
          }
        };
        req.on("close", close);
        req.on("aborted", close);
        return;
      }
      next();
    } catch (error) {
      const message = getErrorMessage(error, "Unknown bridge error");
      setJson(res, 502, { error: message });
    }
  };
  middleware.dispose = () => {
    appServer.dispose();
  };
  return middleware;
}

// src/server/authMiddleware.ts
import { randomBytes, timingSafeEqual } from "crypto";
var TOKEN_COOKIE = "codex_web_local_token";
function constantTimeCompare(a, b) {
  const bufA = Buffer.from(a);
  const bufB = Buffer.from(b);
  if (bufA.length !== bufB.length) return false;
  return timingSafeEqual(bufA, bufB);
}
function parseCookies(header) {
  const cookies = {};
  if (!header) return cookies;
  for (const pair of header.split(";")) {
    const idx = pair.indexOf("=");
    if (idx === -1) continue;
    const key = pair.slice(0, idx).trim();
    const value = pair.slice(idx + 1).trim();
    cookies[key] = value;
  }
  return cookies;
}
var LOGIN_PAGE_HTML = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Codex Web Local &mdash; Login</title>
<style>
*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;background:#0a0a0a;color:#e5e5e5;display:flex;align-items:center;justify-content:center;min-height:100vh;padding:1rem}
.card{background:#171717;border:1px solid #262626;border-radius:12px;padding:2rem;width:100%;max-width:380px}
h1{font-size:1.25rem;font-weight:600;margin-bottom:1.5rem;text-align:center;color:#fafafa}
label{display:block;font-size:.875rem;color:#a3a3a3;margin-bottom:.5rem}
input{width:100%;padding:.625rem .75rem;background:#0a0a0a;border:1px solid #404040;border-radius:8px;color:#fafafa;font-size:1rem;outline:none;transition:border-color .15s}
input:focus{border-color:#3b82f6}
button{width:100%;padding:.625rem;margin-top:1rem;background:#3b82f6;color:#fff;border:none;border-radius:8px;font-size:.9375rem;font-weight:500;cursor:pointer;transition:background .15s}
button:hover{background:#2563eb}
.error{color:#ef4444;font-size:.8125rem;margin-top:.75rem;text-align:center;display:none}
</style>
</head>
<body>
<div class="card">
<h1>Codex Web Local</h1>
<form id="f">
<label for="pw">Password</label>
<input id="pw" name="password" type="password" autocomplete="current-password" autofocus required>
<button type="submit">Sign in</button>
<p class="error" id="err">Incorrect password</p>
</form>
</div>
<script>
const form=document.getElementById('f');
const errEl=document.getElementById('err');
form.addEventListener('submit',async e=>{
  e.preventDefault();
  errEl.style.display='none';
  const res=await fetch('/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({password:document.getElementById('pw').value})});
  if(res.ok){window.location.reload()}else{errEl.style.display='block';document.getElementById('pw').value='';document.getElementById('pw').focus()}
});
</script>
</body>
</html>`;
function createAuthMiddleware(password2) {
  const validTokens = /* @__PURE__ */ new Set();
  return (req, res, next) => {
    if (req.method === "POST" && req.path === "/auth/login") {
      let body = "";
      req.setEncoding("utf8");
      req.on("data", (chunk) => {
        body += chunk;
      });
      req.on("end", () => {
        try {
          const parsed = JSON.parse(body);
          const provided = typeof parsed.password === "string" ? parsed.password : "";
          if (!constantTimeCompare(provided, password2)) {
            res.status(401).json({ error: "Invalid password" });
            return;
          }
          const token2 = randomBytes(32).toString("hex");
          validTokens.add(token2);
          res.setHeader("Set-Cookie", `${TOKEN_COOKIE}=${token2}; Path=/; HttpOnly; SameSite=Strict`);
          res.json({ ok: true });
        } catch {
          res.status(400).json({ error: "Invalid request body" });
        }
      });
      return;
    }
    const cookies = parseCookies(req.headers.cookie);
    const token = cookies[TOKEN_COOKIE];
    if (token && validTokens.has(token)) {
      next();
      return;
    }
    res.setHeader("Content-Type", "text/html; charset=utf-8");
    res.status(200).send(LOGIN_PAGE_HTML);
  };
}

// src/server/httpServer.ts
var __dirname = dirname(fileURLToPath(import.meta.url));
var distDir = join2(__dirname, "..", "dist");
function createServer(options = {}) {
  const app2 = express();
  const bridge = createCodexBridgeMiddleware();
  if (options.password) {
    app2.use(createAuthMiddleware(options.password));
  }
  app2.use(bridge);
  app2.use(express.static(distDir));
  app2.use((_req, res) => {
    res.sendFile(join2(distDir, "index.html"));
  });
  return {
    app: app2,
    dispose: () => bridge.dispose()
  };
}

// src/server/password.ts
import { randomInt } from "crypto";
var CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
function randomGroup(length) {
  let result = "";
  for (let i = 0; i < length; i++) {
    result += CHARS[randomInt(CHARS.length)];
  }
  return result;
}
function generatePassword() {
  return `${randomGroup(3)}-${randomGroup(3)}-${randomGroup(3)}`;
}

// src/cli/index.ts
var program = new Command().name("codex-web-local").description("Web interface for Codex app-server").option("-p, --port <port>", "port to listen on", "3000").option("--password <pass>", "set a specific password").option("--no-password", "disable password protection").parse();
var opts = program.opts();
var port = parseInt(opts.port, 10);
var password;
if (opts.password === false) {
  password = void 0;
} else if (typeof opts.password === "string") {
  password = opts.password;
} else {
  password = generatePassword();
}
var { app, dispose } = createServer({ password });
var server = createServer2(app);
// 安全修复：必须绑定 127.0.0.1。Node 的 server.listen(port) 默认监听
// 全部网卡（::），会把 --no-password 的工作台暴露给同一 Wi-Fi 下的所有
// 设备（等于局域网内任意命令执行）。DESIGN.md 契约：仅回环可访问。
var BIND_HOST = "127.0.0.1";
server.listen(port, BIND_HOST, () => {
  const lines = [
    "",
    "Codex Web Local is running!",
    "",
    `  Local:    http://localhost:${String(port)}`
  ];
  if (password) {
    lines.push(`  Password: ${password}`);
  }
  lines.push("");
  console.log(lines.join("\n"));
});
function shutdown() {
  console.log("\nShutting down...");
  server.close(() => {
    dispose();
    process.exit(0);
  });
  setTimeout(() => {
    dispose();
    process.exit(1);
  }, 5e3).unref();
}
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
//# sourceMappingURL=index.js.map