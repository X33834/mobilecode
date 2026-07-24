# 第三方组件声明

Mobilecode 基于以下开源项目构建，感谢原作者与社区的贡献。

## 运行时内置（打进 APK）

| 组件 | 用途 | 许可证 | 上游 |
|---|---|---|---|
| Termux bootstrap | 嵌入式 Linux 用户态（bash/coreutils/curl 等） | GPL-3.0 | https://github.com/termux/termux-packages |
| OpenAI Codex CLI | AI 编程内核（含 linux-arm64 静态链接原生引擎） | Apache-2.0 | https://github.com/openai/codex |
| codex-web-local | 工作台 Web 界面与服务端 | MIT | https://github.com/pavel-voronin/codex-web-local |
| Node.js 24 | 运行时 | MIT | https://nodejs.org |
| npm | 包管理器（内置在镜像中） | Artistic-2.0 | https://github.com/npm/cli |

## 构建期依赖

- Termux 官方包仓库（`termux-main`）中的 deb 包，用于组装 Node.js 运行时闭包；
- npm registry 中的 `@openai/codex` 发布包；
- Android 工程依赖：AndroidX（core / appcompat / webkit / security-crypto / material），均为 Apache-2.0 许可证。

## 分发说明

- APK 内嵌内容遵循其各自上游许可证；
- Termux bootstrap 为 GPL-3.0，本项目以**独立分离的预组装产物**方式内置分发，不修改其源码；如需对应源码请访问上游链接；
- 如上游许可证要求提供源码获取方式，可联系作者获取。

## 素材

- 应用图标与 README 头图为项目原创（© 2026 badhope）。
