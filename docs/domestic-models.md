# 国产模型接入指南

Mobilecode 从 v0.1.4 起支持在 App 内直接选择国产模型服务商，无需任何额外配置。

## 支持的国产模型

| 服务商 | 默认模型 | 申请地址 | 说明 |
|---|---|---|---|
| DeepSeek | `deepseek-chat` | https://platform.deepseek.com | 性价比高，开发者口碑好 |
| Qwen（通义千问） | `qwen-plus` | https://bailian.console.aliyun.com | 阿里云百炼，DashScope 兼容模式 |
| GLM（智谱） | `glm-4.5` | https://open.bigmodel.cn | 智谱开放平台 |

## 在 App 内使用

1. 到对应平台注册并创建 API Key（各平台通常叫「API Keys」或「访问令牌」）；
2. 打开 App → 点工作台右上角 **⚙ 设置**；
3. 选择服务商（DeepSeek / Qwen / GLM）→ 粘贴 API Key → 确定；
4. App 会自动重启工作台并生效。

Key 只保存在手机本地（Android Keystore 加密），不会上传到任何服务器。

## 原理（二开参考）

v0.6.0 起 Codex 引擎（0.104.0）只讲 Responses API，而 DeepSeek/Qwen/GLM 只提供
Chat Completions。App 内置了零依赖的本地协议桥 **chat-bridge.js**
（127.0.0.1:18925），把引擎的 Responses 请求实时翻译成 Chat 请求转发上游，
再把上游的 Chat SSE 流翻译回 Responses 事件流：

```
codex 引擎 ──Responses──▶ chat-bridge(127.0.0.1:18925) ──Chat──▶ provider
codex 引擎 ◀──Responses SSE── chat-bridge ◀──Chat SSE── provider
```

App 会把所选服务商写进 Codex 配置：

```toml
# ~/.codex/config.toml（App 自动生成）
approval_policy = "never"
sandbox_mode = "danger-full-access"
model = "deepseek-chat"
model_provider = "deepseek"

[model_providers.deepseek]
name = "deepseek"
base_url = "http://127.0.0.1:18925/deepseek/v1"   # 本地协议桥
env_key = "DEEPSEEK_API_KEY"
wire_api = "responses"                             # 0.104.0 仅支持 responses
```

> 注意：`wire_api = "chat"` 在 Codex 0.104.0 已被移除（加载即报错），
> 纯 Chat 上游必须经协议桥转换，或把引擎降级到旧版本。

对应密钥写入 `~/.codex/auth.json`，并在启动工作台时注入环境变量。

## 常见问题

**Q：填了 Key 但提示验证失败？**
A：先确认 Key 在官网可用（余额/额度），再看手机网络是否能直连该平台；也可以稍后重试，App 内验证不阻塞界面。

**Q：想换回 OpenAI？**
A：设置里选回 OpenAI 并填入 Key 即可。
