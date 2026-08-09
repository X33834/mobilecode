# Mobilecode 设计文档

> 手机即开发机：一个 APK 内置完整 Linux 用户态 + AI 编程内核，手机本地运行，模型走在线 API（BYOK）。
> 定位：**不需要电脑、不需要自己的服务器、不需要 root**。装好 APK → 填 API Key → 直接开发。

---

## 1. 总体架构

```
┌─────────────────────────── Android APK ───────────────────────────┐
│                                                                   │
│   WebView（工作台界面，codex-web-local 前端）                      │
│      ▲                                                           │
│      │ http://127.0.0.1:18923                                    │
│   ┌──┴─────────────────────────────────────────────────────────┐ │
│   │  CodexServerManager（Kotlin 编排层）                         │ │
│   │   · 4 线程并行解压镜像分片（首次）                             │ │
│   │   · 启动 CONNECT 代理（DNS/TLS 桥）                          │ │
│   │   · 启动工作台服务器（--no-password，仅监听 127.0.0.1）        │ │
│   │   · 注入用户 API Key 到进程环境                               │ │
│   └──┬─────────────────────────────────────────────────────────┘ │
│   ┌──▼─────────────────────────────────────────────────────────┐ │
│   │  嵌入式 Linux 用户态（files/usr，无 root）                    │ │
│   │  ├── Termux bootstrap（bash/coreutils/curl/…，符号链接相对化）│ │
│   │  ├── Node.js 24 + npm                                        │ │
│   │  ├── OpenAI Codex CLI（静态链接 linux-arm64 原生引擎）         │ │
│   │  └── codex-web-local（dist-cli 工作台服务 + dist 前端）        │ │
│   └─────────────────────────────────────────────────────────────┘ │
│                                                                   │
│   模型推理 → 在线 API（OpenAI / DeepSeek / Qwen / GLM，BYOK）      │
└───────────────────────────────────────────────────────────────────┘
```

关键设计选择：

- **手机本地执行，远程推理**：代码读写、命令执行全部在手机本地 Linux 用户态内完成，只有模型推理走在线 API。满足「不用电脑、不用服务器、本地模型跑不动」三个约束。
- **成品镜像，一次解压**：运行时环境在**构建机**上预组装为单个镜像（v0.3.0 起），设备端只做解压，不再有 dpkg / npm install / 路径修补等设备端安装步骤，彻底解决弱网下安装失败的问题。
- **分片并行解压**（v0.4.0 起）：镜像拆成 4 个独立 gzip+tar 分片，设备端 4 线程并行解压，利用多核 IO 将首次启动耗时降到约 1/3。
- **无需 root**：借 Termux 用户态 + `targetSdk=28` 在应用数据目录内直接执行二进制（Android 10+ 对 targetSdk 29+ 强制 W^X，Termux F-Droid 同款做法）。

## 2. 仓库结构

```
mobilecode/
├── android/
│   ├── app/src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── assets/proxy.js          # CONNECT 代理（Node，本地 DNS/TLS 桥）
│   │   ├── java/com/codex/mobile/
│   │   │   ├── MainActivity.kt      # WebView + 首次启动编排 + 设置/诊断 UI
│   │   │   ├── CodexServerManager.kt# 环境/认证/服务生命周期
│   │   │   ├── TarExtractor.kt      # 流式 tar+gzip 解压（支持 GNU 长名）
│   │   │   ├── SecureKeyStore.kt    # Android Keystore AES/GCM 加密存储
│   │   │   ├── BootstrapInstaller.kt# 路径常量与清理工具
│   │   │   └── CodexForegroundService.kt # 前台服务（后台常驻）
│   │   └── res/                     # 布局 / 主题 / 图标
│   └── scripts/
│       ├── build-image.py           # 镜像构建器（分片 + 瘦身 + 依赖自校验）
│       └── server-bundle/           # 工作台前端与服务端（构建期打进镜像）
├── art/                             # 品牌素材
├── docs/                            # 文档
└── release/                         # APK 发布物
```

## 3. 镜像构建流水线（build-image.py）

构建机（Linux/macOS，无需 Android SDK）上运行：

```
1. 下载 Termux bootstrap-aarch64.zip（含 SYMLINKS.txt）
2. 解析 termux-main Packages 索引 → 求 Node.js 依赖闭包（c-ares/libicu/libsqlite/nodejs-lts/npm）
3. 下载对应 deb，用 ar+tar 解包（不依赖 dpkg）
4. 下载 @openai/codex 与 codex-linux-arm64（锁版本），解出 JS 启动器与原生引擎
5. 组装 usr/ 目录：
   · bootstrap 展开 + SYMLINKS 全部重建为相对符号链接
   · 文本文件里的 /data/data/com.termux/files/usr 改写为最终前缀
   · 写包装脚本（npm/npx/codex → exec node …），shebang 指向最终前缀
6. 瘦身：移除 apt/dpkg 全家、gpg/gnutls 孤儿库簇、info 文档、源码映射
   · 删库前自动校验剩余 ELF 的 NEEDED 依赖完整性
7. 打包：打未压缩 tar → 按条目大小贪心拆成 4 个分片 → 各 gzip 成 images0..3.bin
8. 校验：关键文件（bin/sh、node、codex、dist-cli/index.js、.runtime-version）必须出现在分片中
```

产物 `app/src/main/assets/images0..3.bin` 在构建期写入 APK（`noCompress` 原样存储）。

## 4. 启动流程

```
首次启动：
  路径自愈（/data/user/0 ↔ /data/data 视图对齐）
  → 并行解压 4 分片（~1 分钟，零网络）
  → 写 ~/.codex/config.toml（approval=never, full-access）
  → 初始化默认工作区
  → 启动 CONNECT 代理（proxy.js，本地 DNS/TLS 桥）
  → 启动工作台服务器（codex-web-local，--no-password）
  → 进入 WebView 工作台（后台验证 API Key，不阻塞界面）

非首次：跳过解压，秒进工作台。
```

## 5. API Key 配置与安全

- 用户在 App 内选择服务商（openai / deepseek / qwen / glm）+ 填写 API Key；
- Key 用 **Android Keystore AES/GCM** 加密后存 SharedPreferences（格式 `enc:v1:base64(iv||ct)`），密钥不出安全硬件；
- 配置写入 `~/.codex/config.toml`（注册全部 provider）与 `auth.json`（当前 Key），并注入工作台进程环境变量；
- 保存后自动重启工作台使新密钥立即生效。

## 6. 网络

- 工作台服务器只监听 `127.0.0.1:18923`，App 内 WebView 访问，不暴露到局域网；
- 原生引擎的 HTTPS 调用走本地 CONNECT 代理（`127.0.0.1:18924`），负责 DNS/TLS 桥接，规避 Android 网络栈对 NDK 二进制的限制；
- 唯一出网流量：调用模型 API。

## 7. 已知限制

- **仅 Android / ARM64**：iOS 沙盒无法承载本地 Linux 用户态；
- **首次启动约 1 分钟**：解压 ~80 MB 镜像（已并行化；相比 v0.3.0 之前的在线安装已是质变）；
- **模型推理依赖网络**：国内使用建议选 DeepSeek / Qwen / GLM；
- 应用包名仍为 `com.codex.mobile`（历史沿用，暂未变更）；
- 真机兼容性依赖社区反馈迭代（详见 [docs/ROADMAP.md](docs/ROADMAP.md)）。
