#!/usr/bin/env python3
"""Mock OpenAI-compatible API for runtime verification (dual protocol).

Mobilecode 运行时验证桩：同时提供 Chat Completions 与 Responses 两种协议，
并内置一次「工具调用往返」逻辑，用于在 qemu-aarch64 模拟环境里端到端验证
codex 引擎的完整智能体循环（不依赖任何真实 API Key）：

  /v1/chat/completions  纯 chat 上游（模拟 DeepSeek/Qwen/GLM）
    - messages 中无 role=tool → 下发 tool_calls(exec_command: echo …)
    - 有 role=tool           → 返回含工具输出的最终文本
  /v1/responses         Responses API（模拟 OpenAI）
    - input 中无 function_call_output → 流式下发 function_call（工具名
      取自请求 tools 里引擎自己声明的名字，跨版本稳定）
    - 有 function_call_output        → 返回含工具输出的最终文本
    - SSE 终止事件 response.completed 必发（codex 依赖其判定流结束）

用法：python3 mock-openai.py --http 8080 --tls 8443 --cert cert.pem --key key.pem
"""
import argparse
import json
import ssl
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CALLS = []

ECHO_TEXT = "Hello from mock model. The end-to-end chain (proxy -> TLS -> API) works."
ECHO_CMD = ["echo", "e2e-ok-from-mock"]


def _find_shell_tool(req):
    """从引擎声明的 tools 里找执行类工具（跨版本名字稳定：shell/exec_command）。"""
    for t in req.get("tools") or []:
        name = t.get("name", "") if isinstance(t, dict) else ""
        if "shell" in name or "exec" in name:
            return name
    return None


def _final_response(resp_id, items):
    return {
        "id": resp_id, "object": "response", "status": "completed",
        "output": items,
        "usage": {"input_tokens": 11, "output_tokens": 7, "total_tokens": 18},
    }


def _msg_item(text):
    return {"type": "message", "id": "msg-mock", "role": "assistant",
            "status": "completed",
            "content": [{"type": "output_text", "text": text}]}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("[mock] %s\n" % (fmt % args))

    def _sse(self, etype, payload):
        payload = dict(payload)
        payload.setdefault("type", etype)
        self.wfile.write(("event: %s\n" % etype).encode())
        self.wfile.write(("data: %s\n\n" % json.dumps(payload)).encode())
        self.wfile.flush()

    def _send(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.rstrip("/").endswith("/models"):
            self._send(200, {"object": "list", "data": [{"id": "gpt-mock", "object": "model"}]})
        else:
            self._send(200, {"status": "ok", "path": self.path})

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        req = json.loads(self.rfile.read(n) or b"{}")
        CALLS.append(req)
        if self.path.endswith("/chat/completions"):
            self._chat(req)
        elif self.path.endswith("/responses"):
            if req.get("stream"):
                self._responses_stream(req)
            else:
                self._send(200, _final_response(
                    "resp-mock", [_msg_item(ECHO_TEXT)]))
        else:
            self._send(404, {"error": {"message": "unknown path " + self.path}})

    # ---------- Chat Completions（模拟 DeepSeek 风格纯 chat 上游） ----------

    def _chat(self, req):
        msgs = req.get("messages") or []
        has_tool_result = any(m.get("role") == "tool" for m in msgs if isinstance(m, dict))
        model = req.get("model", "gpt-mock")

        if req.get("stream"):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()

            def chunk(delta, finish=None, usage=None):
                obj = {"id": "chatcmpl-mock", "object": "chat.completion.chunk",
                       "created": 1, "model": model,
                       "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}
                if usage:
                    obj["usage"] = usage
                self._write_chat_sse(obj)

            if has_tool_result:
                tool_out = next(m for m in reversed(msgs) if m.get("role") == "tool")
                text = "CHAT LOOP OK. tool output: " + str(tool_out.get("content", ""))[:300]
                chunk({"role": "assistant", "content": text})
                chunk({}, finish="stop", usage={"prompt_tokens": 20, "completion_tokens": 15, "total_tokens": 35})
            else:
                chunk({"role": "assistant", "tool_calls": [
                    {"index": 0, "id": "call-chat-1", "type": "function",
                     "function": {"name": "exec_command", "arguments": ""}}]})
                chunk({"tool_calls": [{"index": 0, "function": {"arguments": json.dumps({"cmd": "echo chat-loop-ok"})}}]})
                chunk({}, finish="tool_calls")
                chunk({}, finish="stop", usage={"prompt_tokens": 20, "completion_tokens": 10, "total_tokens": 30})
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
        else:
            if has_tool_result:
                msg = {"role": "assistant", "content": "CHAT LOOP OK (non-stream)"}
                finish = "stop"
            else:
                msg = {"role": "assistant", "content": None, "tool_calls": [
                    {"id": "call-chat-1", "type": "function",
                     "function": {"name": "exec_command", "arguments": json.dumps({"cmd": "echo chat-loop-ok"})}}]}
                finish = "tool_calls"
            self._send(200, {"id": "chatcmpl-mock", "object": "chat.completion",
                             "created": 1, "model": model,
                             "choices": [{"index": 0, "message": msg, "finish_reason": finish}],
                             "usage": {"prompt_tokens": 20, "completion_tokens": 10, "total_tokens": 30}})

    def _write_chat_sse(self, obj):
        self.wfile.write(b"data: " + json.dumps(obj).encode() + b"\n\n")
        self.wfile.flush()

    # ---------- Responses API（SSE） ----------

    def _responses_stream(self, req):
        resp_id = "resp-mock-%d" % (len(CALLS) + 1)
        items = self._decide_output(req)

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()

        self._sse("response.created", {"response": {"id": resp_id}})
        for idx, item in enumerate(items):
            self._sse("response.output_item.added",
                      {"output_index": idx, "item": self._preview(item)})
            if item["type"] == "message":
                for ci, part in enumerate(item["content"]):
                    self._sse("response.content_part.added",
                              {"item_id": item["id"], "output_index": idx,
                               "content_index": ci, "part": {"type": "output_text", "text": ""}})
                    self._sse("response.output_text.delta",
                              {"item_id": item["id"], "output_index": idx,
                               "content_index": ci, "delta": part["text"]})
                    self._sse("response.output_text.done",
                              {"item_id": item["id"], "output_index": idx,
                               "content_index": ci, "text": part["text"]})
                    self._sse("response.content_part.done",
                              {"item_id": item["id"], "output_index": idx,
                               "content_index": ci, "part": part})
            elif item["type"] == "function_call":
                self._sse("response.function_call_arguments.delta",
                          {"item_id": item["id"], "output_index": idx, "delta": item["arguments"]})
                self._sse("response.function_call_arguments.done",
                          {"item_id": item["id"], "output_index": idx, "arguments": item["arguments"]})
            self._sse("response.output_item.done", {"output_index": idx, "item": item})
        self._sse("response.completed",
                  {"response": _final_response(resp_id, items)})

    @staticmethod
    def _preview(item):
        p = dict(item)
        if p["type"] == "message":
            p["content"] = [{"type": "output_text", "text": ""}]
        return p

    def _decide_output(self, req):
        inputs = req.get("input")
        if isinstance(inputs, str):
            return [_msg_item(ECHO_TEXT)]
        has_tool_output = any(isinstance(i, dict) and i.get("type") == "function_call_output"
                              for i in (inputs or []))
        if has_tool_output:
            tool_out = next(i for i in inputs if i.get("type") == "function_call_output")
            out_text = tool_out.get("output", "")
            if isinstance(out_text, str) and out_text.strip().startswith("{"):
                try:
                    out_text = json.loads(out_text).get("output", out_text)
                except Exception:
                    pass
            return [_msg_item("E2E OK — real agent loop verified. shell output: %s" % str(out_text)[:300])]
        name = _find_shell_tool(req)
        if not name:
            return [_msg_item(ECHO_TEXT)]
        # cmd 为字符串（exec_command schema）；旧版 shell 工具用 command 数组，
        # 两者对 echo 都成立，统一给字符串并在旧工具名下换数组
        args = (json.dumps({"cmd": " ".join(ECHO_CMD)})
                if name in ("shell", "shell_command")
                else json.dumps({"command": ECHO_CMD}) if name == "local_shell"
                else json.dumps({"cmd": " ".join(ECHO_CMD)}))
        return [{"type": "function_call", "id": "fc-mock-1", "call_id": "call-mock-1",
                 "name": name, "arguments": args}]


def serve(port, tls=False, cert=None, key=None):
    srv = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    if tls:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(cert, key)
        srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
    srv.serve_forever()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--http", type=int, default=8080)
    ap.add_argument("--tls", type=int, default=8443)
    ap.add_argument("--cert", default="/opt/arm64/runtime/mock-cert.pem")
    ap.add_argument("--key", default="/opt/arm64/runtime/mock-key.pem")
    args = ap.parse_args()
    threading.Thread(target=serve, args=(args.http, False), daemon=True).start()
    serve(args.tls, True, args.cert, args.key)
