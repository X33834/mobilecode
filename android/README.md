# Mobilecode — Android 工程

Android 壳 App：WebView 工作台 + 服务编排 + 内置运行时镜像。

## 目录

```
android/
├── app/src/main/
│   ├── assets/proxy.js      # 本地 CONNECT 代理（Node 编写）
│   ├── java/com/codex/mobile/
│   │   ├── MainActivity.kt              # 启动编排 + 设置/诊断 UI
│   │   ├── CodexServerManager.kt        # 环境 / 认证 / 服务生命周期
│   │   ├── TarExtractor.kt              # 流式 tar+gzip 解压
│   │   ├── SecureKeyStore.kt            # Keystore 加密存储
│   │   ├── BootstrapInstaller.kt        # 路径常量 / 清理工具
│   │   └── CodexForegroundService.kt    # 前台服务
│   └── res/                 # 布局 / 主题 / 图标
└── scripts/
    ├── build-image.py       # 构建期预组装运行时镜像（分片 + 瘦身）
    └── server-bundle/       # 工作台前端与服务端（构建期打进镜像）
```

## 构建

```bash
# 1. 预组装运行时镜像（Termux + Node + Codex + 工作台）
python3 scripts/build-image.py
# 产物：app/src/main/assets/images0..3.bin（已在 .gitignore，勿提交）

# 2. 构建 APK
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

前置要求：

- JDK 17
- Android SDK（`ANDROID_HOME` 或 `local.properties` 的 `sdk.dir`）
- 构建镜像需要网络（termux 镜像 + npm registry）

## 说明

- `targetSdk=28`：在应用数据目录内直接执行二进制（Android 10+ 对 targetSdk 29+ 强制 W^X，Termux F-Droid 同款做法）；
- 镜像分片体积较大（约 80 MB），构建脚本产物不入库；
- 完整架构说明见仓库根目录 [DESIGN.md](../DESIGN.md)。
