# 更新日志

## [v0.6.1] - 2026-09-14

**v0.6.0 的补漏与固化：镜像回归扫描抓出最后一处 glibc 残留，端到端验证能力正式入仓**

### 修复

- **第三处 ripgrep 残留（`vendor/.../path/rg` 仍为 glibc）**：v0.6.0 只覆盖了 `@openai/codex/bin/rg`
  与 `vendor/.../codex/rg` 两处，新的回归扫描器抓出 codex 包内还有第三份 rg 副本
  （`codex-linux-arm64/vendor/aarch64-unknown-linux-musl/codex/path/rg`）仍是 glibc 链接 ——
  真机上 Path 工具（文件搜索）调用它时必然失败。`build-image.py` 现在把 Termux bionic rg
  覆盖到全部三处，并新增 `usr/bin/rg` 到镜像产物校验清单。
- **自签证书缺 SAN 导致 rustls 校验失败**：验证器生成的 mock TLS 证书最初只写 CN，而引擎的
  rustls 严格校验 SAN 扩展（不回退 CN）。`_gen_certs()` 已补
  `subjectAltName=IP:127.0.0.1,DNS:localhost`。
- **verify-runtime e2e 的 node 架构错配**：链路 B（协议桥）/链路 C（工作台）曾误用 arm64 node
  在 x86 宿主跑纯 JS（`Exec format error`）。现明确分层：bridge 与工作台用宿主原生 node
  （纯 JS 无平台依赖），引擎（musl aarch64）由 qemu 执行；链路 C 新增 `fakebin/codex` shim
  代理 `spawn("codex")` 到 qemu 引擎（镜像内 `usr/bin/codex` 是设备 wrapper，宿主不可用）。

### 新增：仓库级回归验证器 `android/scripts/verify-runtime.py`

- **`scan`（L2 静态体检，纯 stdlib 无依赖）**：手写 ELF 解析（e_machine / PT_INTERP /
  DT_NEEDED），逐文件校验 aarch64 架构、bionic interpreter、动态依赖在镜像内闭环、
  悬空符号链接、dotslash 引导文件 —— 本次即由它抓出第三处 rg。v0.6.1 镜像 279 个 ELF 全过。
- **`e2e`（qemu 端到端，三链路）**：① Responses 直连 + 工具循环收敛；② chat-bridge
  （Responses⇄Chat 双向翻译）+ 工具循环收敛；③ 工作台开箱启动（HTTP 200 +
  `/codex-api/meta/methods` 方法目录 60 个）。配套 `mock-openai.py`（双协议 + 工具调用往返桩）、
  `run-e2e.sh`（残留进程清理入口）。改引擎/镜像/依赖后重跑 `run-e2e.sh` 即可全量回归。
- 镜像重建：4 分片 85.3 MB / 4141 文件，`.runtime-version 0.6.1`；三链路 e2e 全部实测通过。

### 其它

- versionCode 13 → 14，versionName 0.6.1。

## [v0.6.0] - 2026-09-14

**专项修复「AI 对话在真机上 100% 不可用」的协议层致命缺陷，并补齐 ARM64 执行链路缺口**
（全部修复均在 qemu-aarch64 模拟 Android 环境中端到端实测验证，含真实工具调用循环）：

### 模型协议层致命修复（重点）

- **`wire_api = "chat"` 已被引擎移除（对话 100% 报错的根因）**：Codex 0.104.0 彻底删除了
  Chat Completions 协议支持，加载 `wire_api="chat"` 配置直接报错
  `wire_api = "chat" is no longer supported` —— 而 v0.5.0 及之前 `configureProvider()` 给全部
  4 个 provider 写的都是 `"chat"`，即装好后选任何服务商都无法对话。
  修复：① 新增零依赖本地协议桥 **`chat-bridge.js`**（`assets/`，监听 `127.0.0.1:18925`），
  把引擎的 Responses 请求实时翻译成 Chat Completions 转发上游，再把上游 Chat SSE 流翻译回
  Responses 事件流（含 `function_call` 工具调用双向翻译）；DeepSeek / Qwen / GLM 三个纯 Chat
  上游统一经桥接入。② 全部 provider 写 `wire_api = "responses"`；OpenAI（原生 Responses）引擎直连。
  实测：qemu ARM64 引擎 → bridge → mock(chat) → 引擎真实执行 `echo` 工具 → 结果回传 → 最终回答，
  完整智能体循环跑通。
- **`model = "provider/model"` 复合串无法绑定自定义 provider**：0.104.0 不解析该写法，静默回落
  默认 OpenAI provider（实测：`provider: openai`、请求打到 `api.openai.com`）。改为
  `model` 与 `model_provider` 分离写法。
- **`base_url` 缺 `/v1` 后缀**：原配置 `https://api.openai.com` 会请求到 `/responses`（404）。
  已统一补全版本段。
- **本地回环被 CONNECT 代理劫持**：引擎/工作台对 `127.0.0.1:18925`（协议桥）的请求默认会走
  `HTTPS_PROXY`。全部进程环境注入 `NO_PROXY=127.0.0.1,localhost`。

### ARM64 执行链路缺口补齐（六层逐层体检发现）

- **ripgrep 在 Android 上不可执行（文件搜索工具瘫痪）**：codex npm 包内的 `bin/rg` 是 dotslash
  引导文件（Android 无法执行）、`codex-linux-arm64/vendor/.../rg` 是 glibc 链接（Android 无
  glibc）。镜像新增 Termux bionic aarch64 ripgrep（含 pcre2 依赖闭包），并覆盖包内两处 rg 副本。
- **工作台缺运行依赖（工作台永远起不来）**：`codex-web-local/dist-cli/index.js` 依赖
  `express`/`commander`，但 bundle 未带 node_modules —— 设备上启动即 `ERR_MODULE_NOT_FOUND`。
  `build-image.py` 构建期安装（`--omit=dev --ignore-scripts`，纯 JS 无平台产物）。
- **3 个悬空符号链接**（`libnettle.so.8` / `libhogweed.so` / `bin/xdg-open`）：瘦身删库后残留死链，
  现构建尾全树扫描清理。
- 镜像重建：4 分片共 85.3 MB（4141 个文件），`.runtime-version 0.6.0`（设备端检测到版本变化自动
  一次性重装），瘦身后动态依赖校验通过。
- **验证方式**：qemu-aarch64 + glibc sysroot 模拟 Android 用户态，四条链路全部实测通过：
  引擎启动/配置解析、镜像 4 分片解压、工作台开箱启动（HTTP 200，仅绑 127.0.0.1）、
  mock(responses) 直连与 mock(chat) 经桥两条完整工具调用循环。

### 其它

- versionCode 12 → 13，versionName 0.6.0；`docs/domestic-models.md` 原理说明同步更新。

## [v0.5.0] - 2026-09-14

**专项修复「APK 装不上 / 构建不出来」，并做一轮安全加固**（对 release APK 二进制做逆向级检查后定位）：

### 安装失败根因修复（重点）

- **签名方案不完整（安装失败主因）**：v0.4.x 的 APK 是 **v2-only 签名**（AGP 在 `minSdk>=24` 时默认关闭
  v1 JAR 签名），且使用 **Android Debug 调试证书**。后果：① debug 证书在 MIUI 等系统上默认被拦截；
  ② 换任何一台构建机出的 debug 证书都不同，老用户升级必然 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
  （签名不一致），先装过其它来源版本的也会因证书不同直接安装失败。
  现在：项目自带正式 `keystore/mobilecode-release.jks`，debug 与 release 构建统一签名（正式证书 +
  v2+v3 方案，v3 支持后续密钥轮换），任何构建机产物可互相覆盖安装。
  注：v1 签名在 `minSdk>=24` 时被 AGP 强制关闭，但所有可安装设备（Android 7.0+）均支持 v2，无实际影响。
- **`gradle.properties` 沙箱污染**：仓库里硬编码了原作者构建机的 `org.gradle.java.home`（mise 路径）
  和 `127.0.0.1:18080` 代理 —— 任何人克隆后第一次构建必失败。已移除，改为注释示例。
- **构建依赖国内不可达**：`services.gradle.org` / `dl.google.com` / `github.com` 直连不稳。
  Gradle 发行版换腾讯云镜像；`settings.gradle.kts` 加阿里云 google/gradle-plugin/public 镜像（官方源
  兜底）；`build-image.py` 的 bootstrap 下载加 ghproxy 系加速 fallback。
- **版本号**：versionCode 11 → 12，versionName 0.5.0。

### 安全修复（逆向检查新发现）

- **工作台服务器监听在 0.0.0.0（高危）**：`codex-web-local` 的 `server.listen(port)` 未绑定回环地址，
  Node 默认监听所有网卡 —— `--no-password` + `danger-full-access` 意味着同一 Wi-Fi 下任何设备打开
  `http://<手机IP>:18923` 即可控制你的 AI 编程环境（任意命令执行）。DESIGN.md 声称"仅监听
  127.0.0.1"，实现并未做到。已改为强制绑定 `127.0.0.1`。
- **明文流量收紧**：全局 `usesCleartextTraffic=true` 改为 networkSecurityConfig —— 仅 127.0.0.1 /
  localhost 允许明文，其余必须 HTTPS。
- **关闭 allowBackup**：避免数百 MB 运行环境被系统备份，也避免 Keystore 加密的 API Key 被恢复到
  其它设备后无法解密的混乱状态。

### 运行时健壮性

- **进程退出清理**：`stopServer()` / `healthCheck()` 超时后只 `destroy()` 不等待，node 卡在 IO 时进程
  残留、端口被占，之后每次启动 `EADDRINUSE` 失败（只能重启手机）。新增 `destroyProcess()`：
  SIGTERM → 等待 3s → SIGKILL。同时 `stopServer()` 在主线程（`onDestroy`）无上限 `waitFor()` 的
  ANR 风险一并消除。
- **WebView 外链**：非 127.0.0.1 的链接交给系统浏览器打开，用户不再被困在内嵌 WebView 里。
- **build-image.py**：deb 支持 zstd 压缩（Termux 新包切换中，原脚本只认 xz，遇到即崩）。

### 构建产物

- 镜像重建：4 分片共 79.4 MB（3384 个文件），瘦身后动态依赖校验通过
- APK：`versionName 0.5.0`（versionCode 12），apksigner 校验 v2+v3 通过，
  debug/release 证书一致（SHA-256 3cd8ba5e…66）

## [v0.4.1] - 2026-09-13

**修复「打开就提示构建失败、环境完全没法用」的根因**（从底层到顶层全链路排查）：

- **镜像版本失配导致每次启动全量重解压**（根因）：APK 期望 `RUNTIME_IMAGE_VERSION=0.4.1`，但内置镜像
  `.runtime-version` 仍是 `0.4.0`。`needsInstall()` 每次启动都判定需要重装 → 删除并重解压 207MB
  环境 → 解压稍有不顺即报「内置运行环境解压失败」，即使成功下次启动又重来一遍，永远进不去工作台。
  已重建镜像，内置版本 0.4.1 与代码一致。
- **TarExtractor 符号链接并发健壮性**：4 分片并行解压时，符号链接的父目录条目可能落在别的分片，
  `Os.symlink` 会抛 ENOENT 导致整片失败。解压符号链接前先 `mkdirs` 父目录。
- **PAX/GNU 长名覆盖顺序**：长名（PAX `path` / GNU `L`）给出完整路径，必须先拼 ustar 前缀、
  再整体覆盖，避免路径重复。
- 保留此前修复：PAX `x` 扩展头解析、文件数据后 512 字节对齐 skipPad、base-256 数值解析、
  代理启动端口验证、UI 线程安全。

**QA 验证（构建机全链路）**：

- 镜像重建：4 分片共 3384 条目，瘦身后动态依赖校验通过
- APK 构建成功：`versionName 0.4.1`（versionCode 11），内置镜像版本 0.4.1 与代码一致
- 解压一致性：用真实 `TarExtractor`（`android.system.Os` JVM 实现）4 线程并行解压，
  与 `tar xzf` 参考解压全量 diff —— 2635 文件 / 545 目录 / 203 符号链接，
  内容 SHA-256、符号链接目标、可执行位完全一致，2.5 秒完成

## [v0.1.0] - 2026-07-31

- 首个可用版本：APK 安装 → 解压运行环境 → 填 API Key → 进入工作台对话
