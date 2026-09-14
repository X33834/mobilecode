# 更新日志

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
