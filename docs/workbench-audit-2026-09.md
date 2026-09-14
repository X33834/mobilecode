# 工作台前端对标 2026 检查报告（2026-09）

> 结论先行：**引擎与运行时已是完整可用的 2026 级 agent 底座；短板集中在前端移动适配与能力暴露。**
> 本报告基于真实运行环境（qemu ARM64 引擎 + mock 上游）+ Playwright 浏览器实测 + 前端 bundle 逆向 + Kotlin 集成审查得出。

## 一、工作台是什么

App 首页即内嵌 WebView 加载的本地工作台（上游 [codex-web-local](https://github.com/pavel-voronin/codex-web-local) v0.1.0，React + Vite + Tailwind，构建产物 238KB）：

```
WebView → http://127.0.0.1:18923（仅本机绑定）
        → dist-cli（express，JSON-RPC 透传 + SSE）
        → codex app-server（stdin/stdout）
        → 引擎 → 上游模型
```

服务端 API 面：`/codex-api/rpc`（60 个方法全透传）、`/codex-api/events`（SSE 通知）、
`/codex-api/server-requests/*`（审批通道）、`/codex-api/meta/*`。

## 二、实测确认的能力

- ✅ 会话列表（4s 轮询可开关）/ 恢复（thread/resume）/ 归档 / 置顶 / 新建
- ✅ 模型下拉（model/list）+ 推理力度 none→xhigh 六档
- ✅ 发送 / 停止（turn/interrupt）；审批响应通道已在代码中
- ✅ **持续对话全链路真实可用**（本报告实测）：
  `thread/start → turn/start → 引擎经 qemu 真实执行 echo 工具 → 输出回传 → 回答收敛（E2E OK — real agent loop verified）→ turn completed → rollout 落盘 → 刷新后会话恢复可继续`
- ✅ WebView 集成（Kotlin）：JS/DOMStorage、外链交系统浏览器、返回键、控制台日志桥接

桌面视图：

![工作台桌面视图](assets/workbench-desktop.png)

## 三、缺陷清单（实测证据）

### P0-1：移动端布局破版

390×844（典型手机）下侧栏不折叠，占屏约 2/3，会话主区与输入框被挤出屏幕外。
上游为桌面浏览器设计，无移动端断点。

![移动端破版](assets/workbench-mobile.png)

### P0-2：新装用户无法发出第一条消息（UX 死锁）

前端逻辑（bundle 逆向确认）：目录下拉的选项 = 历史会话 cwd 去重集合；
新装 app 无任何历史 → 下拉为空且 disabled。
而会话（thread）在首条用户消息前不物化、不进 Threads 列表——循环依赖。
上游桌面场景靠浏览器目录选择器规避，Android WebView 内无此途径。

实测：直接经 RPC 创建会话并发消息成功后，刷新页面 UI 即恢复正常（选项出现、列表可用）——
死锁只卡"从零到第一条消息"这一步。

### P1：渲染与透明度

- 无 markdown / 代码高亮渲染（bundle 中 0 处 markdown/marked/highlight），agent 输出代码块按纯文本展示
- 工具执行过程不展示：`thread/read` 的 turn items 仅含 userMessage/agentMessage，
  用户看不到 agent 执行了什么命令、输出与 diff
- 无图片/文件上传（引擎 InputItem 支持 image，UI 未接）

### P2：能力闲置与体验短板

- 前端仅使用引擎 60 个 RPC 方法中的 **9 个**；skills（×4）、MCP（×3）、review/start、
  turn/steer（对话中途转向）、thread/compact（上下文压缩）、thread/rollback、
  gitDiffToRemote、fuzzyFileSearch、rateLimits 等全部无 UI 入口
- 无语音输入；无用量/速率显示；无 i18n（上游残留俄语 aria-label "Стоп"）
- 主题跟随系统暗色模式未确认

## 四、修复路线

| 优先级 | 项 | 方案草案 |
|---|---|---|
| P0 | 目录死锁 | dist-cli 支持 `--roots` 下发目录列表（或 Kotlin 端经 RPC 预置默认 cwd 引导会话） |
| P0 | 侧栏折叠 | 前端加移动端断点（`max-md` 隐藏侧栏 + 抽屉式展开）；WebView 配 adjustResize |
| P1 | markdown 渲染 | marked + highlight.js（约 60KB），注意 XSS 转义 |
| P1 | 工具过程展示 | 消费 SSE 的 exec 通知渲染可折叠步骤卡片 |
| P1 | 图片上传 | 输入框接引擎 image InputItem |
| P2 | Skills/MCP 管理页 | 直接调已有 RPC |
| P2 | 语音输入 | Android SpeechRecognizer → 输入框 |
| P2 | i18n / 用量 | 顺带清理俄语残留 |

## 五、一句话总结

**引擎是 2026 级的，前端是 2024 级的**——两个 P0 都是小改动可解，修复后工作台即可达到当前主流 agent 产品的可用线。
