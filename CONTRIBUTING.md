# 贡献指南

感谢你有兴趣为 Mobilecode 贡献代码！无论是修 bug、加功能、写文档还是提建议，都欢迎。

## 项目规模说明

Mobilecode 是一个小团队 / 个人维护的项目，仓库结构刻意保持精简。如果你有大的改动想法，建议先开 Issue 聊一下，避免白写。

## 提 Issue

- 先说清楚：设备型号、Android 版本、App 版本号（设置 → 诊断与环境里能看到）
- 描述复现步骤、期望行为、实际行为
- 有日志或截图更好（App 内日志可通过 adb logcat 抓取）

## 提 Pull Request

1. Fork 本仓库并 clone 到本地；
2. 创建功能分支：`git checkout -b feat/xxx`（或 `fix/xxx`）；
3. 改代码，保持风格与现有代码一致；
4. 自测：本仓库提供完整构建脚本（见下），PR 前至少保证能通过构建；
5. 提交并推送，开 PR 时描述清楚改动内容与验证方式。

### 本地构建

```bash
cd android

# 1. 预组装运行时镜像（Termux + Node + Codex + 工作台）
#    需要能访问 termux 镜像与 npm registry；产物约 80 MB
python3 scripts/build-image.py

# 2. 构建 APK（JDK 17 + Android SDK）
./gradlew :app:assembleDebug
```

注意：

- 构建脚本的产物（`app/src/main/assets/images*.bin`）体积较大，**不要提交到仓库**，已在 `.gitignore` 里排除；
- `local.properties`、`gradle.properties` 里的机器相关配置（SDK 路径、代理等）不要提交。

## 代码风格

- Kotlin：官方风格（`kotlin.code.style=official`），4 空格缩进；
- Python（构建脚本）：Python 3.8+，标准库优先，注释用中文；
- 提交信息用中文，格式参考 `feat:` / `fix:` / `perf:` / `refactor:` / `docs:` / `chore:` 前缀。

## 行为准则

请友善沟通，就事论事。本项目对骚扰、攻击性言论零容忍。
