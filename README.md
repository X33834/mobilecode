# Mobilecode（手机 AI 编程）

> 手机即开发机：装一个 APK → 填你自己的 API Key → 让 AI 在手机里帮你写代码、跑命令。不需要电脑，不需要服务器。

- 平台：Android 7.0+（ARM64）
- 模型：OpenAI（默认）；国产模型预设规划中
- 架构：APK 内置 Linux 用户态（Termux），首次启动自动安装 Node.js / Codex CLI 并进入工作台

## 快速开始

1. 从 `release/` 下载 APK 并安装（需开启「允许安装未知来源」）；
2. 首次启动自动解压运行环境并安装依赖（需联网，一次性）；
3. 粘贴你的 API Key，进入工作台直接对话。

## 当前状态

早期 MVP 阶段，功能与稳定性持续迭代中。更新记录见 [CHANGELOG.md](CHANGELOG.md)。
