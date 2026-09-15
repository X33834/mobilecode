# 诊断报告：手机上「模型配置完全用不了」

> 结论：**不是崩溃，是配置面根本没做完整 + 国内模型依赖的协议桥有缺陷。**
> 对标 GPT / Trae / Qoder，v0.8.0 已把模型配置从「2 字段弹窗」升级为完整移动端设置页。

---

## 一、现象

App 能在手机上安装、打开、进入工作台，但点右上角 ⚙：

- 只能从 **4 个写死服务商**（openai / deepseek / qwen / glm）里选 1 个；
- 只能 **填 1 个 API Key**；
- **没有模型选择、没有自定义 Base URL、没有自定义供应商、没有任何参数**；
- 模型被写死（`gpt-4.1-mini` / `deepseek-chat` / `qwen-plus` / `glm-4.5`）。

对用户而言，这就是「模型配置完全用不了」——因为**根本没有可配置的模型**。

---

## 二、根因逐项

### 1. 设置 UI 只是个简陋的 `AlertDialog`（核心）

`MainActivity.showApiConfigDialog()` 里硬编码：

```kotlin
val providers = arrayOf("openai", "deepseek", "qwen", "glm")
```

配好 Key 后，`CodexServerManager.configureProvider()` 把模型**写死**成 spec 里的固定值，
用户无法切换模型、无法填 Base URL、无法接 OpenAI 兼容代理 / 自建 / 本地端点。
这正是国内开发者（以及本项目作者）最常用、最需要的入口，此前完全缺失。

### 2. 配置能传到引擎，但可选性为零

数据流本身是通的（`dist-cli` 用 `spawn("codex", ["app-server"])` **不传 `env`** → Node 默认把
父进程 env 继承给子进程，Key 与代理变量都到了引擎；`config.toml` / `auth.json` 也正确写入
`~/.codex/`）。所以**不是"配置不生效"，而是"没有可配置的东西"**。

### 3. 国内模型全押在一个脆弱的协议桥上

DeepSeek / Qwen / GLM 只提供 Chat Completions，而 Codex 0.104.0 引擎只讲 Responses API，
必须经由本地 `chat-bridge`（`:18925`）翻译。`chat-bridge.js` 在 **「文本 + 工具调用」混合回合**
里有个 finalize 缺陷：`closeText()` 原先是**空操作**，文本项没有在工具调用之前正确闭合——
而 coding agent 恰恰是「先说一句 → 调工具执行 → 再继续」的典型场景，最容易踩。

### 4. 本地 CONNECT 代理是否被引擎真正使用，存在不确定性

`startServer()` 给工作台进程注入 `HTTPS_PROXY=http://127.0.0.1:18924`，但 `dist-cli` 源码里
**完全没有引用** `HTTPS_PROXY` / 任何代理 agent。Node 默认不读代理环境变量；若 Rust 写的
Codex 引擎也不读，则代理形同虚设（此时引擎直连，正常网络下反而能工作）。这是潜在风险点，
本轮未改代理机制，仅记录。

---

## 三、v0.8.0 修复与补全

| 维度 | 修复前 | 修复后 |
|---|---|---|
| 供应商 | 4 个写死 | 8 家（OpenAI / OpenRouter / DeepSeek / Qwen / GLM / Moonshot / Ollama / 自定义） |
| 模型 | 写死 1 个 | 预设列表 + 自定义模型名 |
| Base URL | 不支持 | 支持（OpenAI 兼容代理 / 自建 / 本地） |
| 端点类型 | 无 | 自定义供应商可选 Responses 直连 / Chat 经桥 |
| 设置 UI | 2 字段 `AlertDialog` | 完整移动端设置页（分区 + 大触控目标 + 滚动） |
| 主题 | 仅跟随系统 | 浅色 / 深色 / 跟随系统 |
| 协议桥 | 文本+工具混合回合缺陷 | 已修复（含集成测试验证） |

**关键文件改动**：

- `android/app/src/main/java/com/codex/mobile/CodexServerManager.kt`：扩展 `ProviderSpec` /
  `providers` 目录，新增 `configureProvider(..., baseUrl, endpointType)` 与 getter；config.toml
  / auth.json 完整往返。
- `android/app/src/main/java/com/codex/mobile/SettingsActivity.kt`（新增）：完整设置页。
- `android/app/src/main/res/layout/activity_settings.xml`（新增）+ `strings.xml` + `themes.xml` 样式。
- `android/app/src/main/AndroidManifest.xml`：注册 `SettingsActivity`。
- `android/app/src/main/java/com/codex/mobile/MainActivity.kt`：⚙ 改启动设置页；`onResume`
  据 prefs 标志重启工作台以加载新配置。
- `android/app/src/main/assets/chat-bridge.js`：`closeText()` 修复 + `CHAT_BRIDGE_ROUTES` 自定义上游。

---

## 四、仍待跟进（未在本轮处理）

1. **CONNECT 代理机制复核**：确认 Codex 引擎是否真正使用 `HTTPS_PROXY`；若不使用，
   应明确移除或在引擎侧显式装配代理 dispatcher（避免误导与潜在故障）。
2. **工作台内模型下拉与 config.toml 的联动**：原生设置页是主要配置入口；工作台自带的模型下拉
   行为需复核，避免两处不一致。
3. **真机编译验证**：本环境无 Android SDK，改动未跑 `gradle` 编译，已逐行核对但需在真机/CI 验证。
