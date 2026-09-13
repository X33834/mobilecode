# Mobilecode（Android）

Android APK，内置完整 Linux 用户态（Termux 成品镜像），首次启动一次解压即可运行
Node.js 24 + Codex CLI + codex-web-local 工作台，全程离线安装环境（唯一需要联网的环节是用户调用模型 API）。

## 架构

```
┌─────────────────────────────────────────┐
│              Android APK                │
│                                         │
│  ┌──────────────┐  ┌────────────────┐   │
│  │   WebView    │  │  4 线程并行解压   │   │
│  │  localhost:  │  │  assets/       │   │
│  │   18923      │  │  images0..3.bin│   │
│  └──────┬───────┘  └───────┬────────┘   │
│         │                  ▼            │
│  ┌──────────────────────────────────┐   │
│  │  files/usr/  (Termux prefix)     │   │
│  │    ├── bin/node                  │   │
│  │    ├── bin/codex                 │   │
│  │    └── lib/node_modules/         │   │
│  │        └── codex-web-local/      │   │
│  └──────────────────────────────────┘   │
└─────────────────────────────────────────┘
```

- 运行环境由 [scripts/build-image.py](android/scripts/build-image.py) 在构建机预组装为**成品镜像**
  （Termux bootstrap + Node + Codex + 工作台，路径/权限/符号链接全部固化），产物是
  `app/src/main/assets/images0..3.bin` 四个独立 gzip+tar 分片，设备端 4 线程并行解压。
- 镜像内写死最终前缀 `/data/user/0/com.codex.mobile/files/usr`，设备端一次解压即用，
  无任何在线安装/apt/npm/包装步骤。

## 构建（从源码到 APK）

前置要求：

- JDK 17（`JAVA_HOME` 指向 17；AGP 8.7/Kotlin 2.1 不支持 JDK 21+）
- Android SDK（`android/local.properties` 写入 `sdk.dir=...`，首次需 `sdkmanager --licenses`）
- Python 3.10+
- 构建镜像需联网一次：下载 Termux bootstrap / deb / Codex（共约 150 MB，缓存于
  `scripts/.cache`；镜像产物约 80 MB）

```bash
cd android

# 1. 预组装运行时镜像（产物：app/src/main/assets/images0..3.bin）
python3 scripts/build-image.py

# 2. 构建 APK
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk（约 90 MB）
```

> 第 1 步不能跳过：images0..3.bin 是 App 首启必需，缺失/版本不匹配会导致
> 「打开就提示构建失败」。

## 排障

- **构建失败（Gradle）**：检查 `android/gradle.properties` 末尾的
  `systemProp.http.proxyHost/Port`（本沙箱构建专用）与
  `org.gradle.java.home`（指向本机 JDK 17 路径）。换机器构建时请删除/改为你本机的 JDK 17
  路径；没有本地代理的机器应删掉两行 proxy 配置。
- **App 内报「内置运行环境解压失败」**：点「重试」重新解压；仍失败点「诊断与环境」
  查看存储剩余（需 >500 MB）与「重置环境」。
- **版本对不上**：镜像内部 `.runtime-version` 必须等于
  `CodexServerManager.RUNTIME_IMAGE_VERSION`，否则每次启动都会全量重解压
  （这是 v0.4.0 之前「完全没法用」的根因，v0.4.1 已修复）。

## 首次运行

1. 解压内置环境（4 线程并行，约 1 分钟）
2. 启动本地网络代理（CONNECT 桥，供 Codex 原生二进制访问 HTTPS）
3. 进入工作台 → 右上角 ⚙ 配置 API Key（支持 openai / deepseek / qwen / glm）

## 最低要求

- Android 7.0 (API 24) 及以上，arm64 设备
- 约 500 MB 可用存储
- 调用模型 API 需联网
