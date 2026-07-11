# TAgent 功能演示录屏

这些录屏按功能拆分保存，便于后续只替换发生变化的片段。录制脚本位于 `scripts/录制演示视频.cjs`。

## 录屏列表

| 序号 | 功能 | 文件 |
| --- | --- | --- |
| 01 | Fixed / Auto / Flow 三种策略基础问答 | `01-三种策略基础问答.mp4` |
| 02 | 自动路由选择 Agent | `02-自动路由选择智能体.mp4` |
| 03 | 执行期 `request_tool` 动态补挂工具 | `03-执行期动态补挂工具.webm` |
| 04 | `ask_user` 主动询问用户补充信息 | `04-主动追问.webm` |
| 05 | Flow 计划确认、编辑与执行 | `05-流程计划确认编辑.webm` |
| 06 | Auto 引导与立即回答 | `06-自动模式引导立即回答.mp4` |
| 07 | 记忆与 RAG 依据展示 | `07-记忆与RAG依据.mp4` |
| 08 | 高危工具人工审批（批准执行） | `08-高危工具人工审批.mp4` |
| 09 | 运行编号步骤级重做 | `09-运行编号步骤重做.mp4` |
| 10 | Token 消耗与 MCP 观测页面 | `10-令牌消耗与MCP观测.webm` |

## 重新录制

确保 `ai-agent-station-study` 已在本机启动并开放 `http://localhost:8099`。首次录制时先安装依赖：

```bash
npm install --prefix docs/demo-videos
```

然后执行：

```bash
node docs/demo-videos/scripts/录制演示视频.cjs
```

只重录单个片段：

```bash
node docs/demo-videos/scripts/录制演示视频.cjs --only=05
```

默认使用 `admin / 123456` 登录，可通过环境变量覆盖：

```bash
TAGENT_DEMO_URL=http://localhost:8099 TAGENT_DEMO_USER=admin TAGENT_DEMO_PASSWORD=123456 node docs/demo-videos/scripts/录制演示视频.cjs
```

默认按 `1280x720` 录制，可通过 `TAGENT_DEMO_WIDTH` / `TAGENT_DEMO_HEIGHT` 覆盖；默认不叠加说明浮层，设置 `TAGENT_DEMO_OVERLAY=1` 可开启。脚本原生输出 WebM；当前 01、02、06、07、08、09 为人工复核后的 MP4 成片。

第 09 段依赖一条尚未过期的历史运行快照，重录时必须显式提供来源会话和命令，避免脚本错误地使用作者本地历史数据：

```bash
TAGENT_DEMO_REDO_SESSION_ID=session_xxx TAGENT_DEMO_REDO_COMMAND="/runId-stepN 修正要求" node docs/demo-videos/scripts/录制演示视频.cjs --only=09
```
