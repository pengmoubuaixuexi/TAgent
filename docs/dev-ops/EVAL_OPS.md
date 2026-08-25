# EvalOps 评测平台

## 能力范围

- 可视化创建数据集、编辑题目和显式评测规则。
- 数据集使用 `DRAFT -> PUBLISHED` 版本流；发布版本不可修改，发布后自动创建下一版草稿。
- 支持 `INDEPENDENT` 独立样本和 `SCENARIO` 有状态会话场景。
- 复用正式 Agent Dispatch、RunSnapshot 和 RunEvent 链路执行题目，不再从网页启动 JUnit。
- 每题持久化答案、Agent/策略、Trace、信号、九维规则分、等级和扣分原因。
- 规则评测完成后，可选择全部、规则低分或失败题目发起独立 LLM Judge。
- LLM Judge 持久化 correctness/relevance/completeness/usefulness/safety、overall、verdict 和问题列表。
- 每次评测冻结当前运行实例的有效源码 Git Tree；下次评测前自动为历史 `WAITING_TAG` 记录匹配 Tag，也支持手动校验和绑定。

## 首次启用

先执行数据库迁移：

```powershell
./docs/dev-ops/run-migrations.ps1
```

现有 EvalOps 环境升级时必须包含：

- `V059__create_eval_ops.sql`
- `V060__create_eval_code_version_binding.sql`

应用启动后访问：

```text
http://localhost:8099/eval.html
```

聊天页顶部也增加了 `EvalOps` 入口。

## E2E100 迁移

工作台空状态中的“一键迁移 E2E100”会读取 `classpath:eval/e2e100.json`：

1. 创建 `SCENARIO` 数据集；
2. 把 100 道题放进同一个 `e2e100-main` 会话组；
3. 将原先按题号/关键词推断的配置转换为显式 `config_json`；
4. 发布 v1；
5. 自动创建内容相同、可继续编辑的 v2 草稿。

E2E100 原链路依赖用户 `10001` 的 RAG 数据时，发起评测应选择 `FIXED_USER` 并填写 `10001`。普通回归评测推荐 `ISOLATED_USER`，防止历史 LTM 污染。

## Agent 14维场景基准集

数据集主页可一键导入 `Agent 14维场景基准集（80题版）`。该集合包含 80 道题、16 个按 `conversationGroup` 隔离的会话场景，使用显式配置或命名配置档案，不依赖关键词推断；如果旧的24题版已经存在，也会作为独立数据集保留：

- 规则 9 维：route、answer、step、tool、grounding、memory、stability、efficiency、safety。
- Judge 5 维：correctness、relevance、completeness、usefulness、safety。
- 场景组：资料记忆与纠错、长期记忆应用、专业路由与路由广度、实时工具与证据、RAG 知识库、理财与通用风险边界、确定性答案、指令遵循、复杂工作流、澄清与异常恢复等。

每题都保存 `referenceAnswer` 作为 Judge 判定要点。Judge 允许语义等价表达，不要求逐字匹配；动态天气、行情、路线和版本信息仍以本次真实工具证据为准。该集合包含 RAG 题，完整评测建议选择 `FIXED_USER` 并使用已准备知识库数据的用户 `10001`。

Judge 详情会按 `resultId` 关联回题目快照和规则结果，展示题目、参考答案、实际答案、Agent/策略、规则九维、Judge 五维及原始 Judge JSON；支持按表头排序、单题 JSON 复制/下载和整批 JSON 导出。规则结果表支持按题号、状态、Agent、策略、规则总分、耗时和观察数量排序。

`agent-rules-v2` 取消了统一的 80 字答案门槛：只有题目显式配置 `minAnswerLength` 时才检查长度。关键词匹配会归一化大小写、空白和标点，并支持用 `|` 表示同义候选；空答案、长度不足、缺少必须内容和出现禁止内容会分别报告。对于明确路由能力不匹配并导致 RAG 无证据的情况，grounding 原始分仍保留用于诊断，但会标记为上游阻断，不再重复影响规则总分。

## 并发与场景隔离

- `SCENARIO` 固定为并发 1：同一会话组内共享 Session，并按题目顺序执行；题间等待只对该模式生效。
- `INDEPENDENT` 的并发数表示单次评测内部最多同时执行的独立题目数，每题使用独立 Session。
- 多个 EvalRun 当前可以同时执行。不同 Run 的 Session ID 不同，但会竞争模型 API、数据库和工具资源；使用同一个 `FIXED_USER` 时还会共享长期记忆、情景记忆与用户 RAG 权限。因此场景记忆回归建议等待前一次 Run 结束后再启动下一次。

## 耗时预算与硬超时

- 题目配置中的 `maxLatencyMs` 是效率目标线，不是中断时间。超过该值会记录“耗时超过预算”并降低 efficiency 分，但 Agent 会继续执行。
- 所有题目的统一硬超时为 1800 秒。只有达到该上限仍未结束时，平台才调用 `cancelExecute` 并将题目标记为 `TIMEOUT`。
- `SCENARIO` 的题间等待不计入单题执行时间，整次 EvalRun 当前没有总时长上限。

## 状态流

```text
DatasetVersion: DRAFT -> PUBLISHED

EvalRun: QUEUED -> RUNNING -> RULE_COMPLETED
                              -> JUDGING -> COMPLETED
                    \-> FAILED
                    \-> CANCELLED

JudgeJob: QUEUED -> RUNNING -> COMPLETED/PARTIAL/FAILED/CANCELLED

CodeVersion: WAITING_TAG -> AUTO_VERIFIED/MANUAL_VERIFIED
                         \-> MANUAL_UNVERIFIED
```

## 代码版本双阶段绑定

应用启动时会读取当前 Git 工作区，用临时 Index 和临时对象目录生成过滤后的 Git Tree Hash；不会修改真实 Git Index。评测创建时将这个运行实例快照复制到评测记录，保证随后修改磁盘源码不会改变历史记录。

评测完成后可以先提交代码、再创建 Tag。下一次发起评测前，平台会扫描尚未处理的 Tag，用历史评测保存的忽略规则重新计算 Tag Tree Hash；完全一致时自动标记为 `AUTO_VERIFIED`。没有下一次评测时，也可以在详情页手动选择 Tag：一致为 `MANUAL_VERIFIED`，强制关联必须填写原因并标记为 `MANUAL_UNVERIFIED`。

根目录 `.evalopsignore` 独立于 `.gitignore`，默认排除日志、`target`/`build`、测试源码、临时目录、文档与历史评测报告、图片、视频和 PDF，只保留会影响应用运行、配置和数据库结构的文件。每次评测同时保存忽略规则快照，后续校验不会受到规则变更影响。

如果应用不是从 Git 工作区运行，可通过 `eval.ops.git.repository-path` 指向本机仓库；仍不可访问时，代码快照状态为 `CAPTURE_UNAVAILABLE`。

## 核心接口

| 方法 | 路径 | 用途 |
|---|---|---|
| GET/POST | `/api/v1/eval/datasets` | 查询或创建数据集 |
| DELETE | `/api/v1/eval/datasets/{datasetId}` | 删除数据集及其全部版本和历史评测（存在活跃评测时拒绝） |
| POST | `/api/v1/eval/datasets/{datasetId}/cases` | 新增草稿题目 |
| POST | `/api/v1/eval/datasets/{datasetId}/publish` | 发布版本 |
| POST | `/api/v1/eval/datasets/import/e2e100` | 迁移 E2E100 |
| POST | `/api/v1/eval/datasets/import/quality-benchmark` | 导入 Agent 14维场景基准集 |
| POST/GET | `/api/v1/eval/runs` | 发起或查询评测 |
| GET | `/api/v1/eval/runs/{evalRunId}` | Run 汇总及逐题结果 |
| POST | `/api/v1/eval/runs/{evalRunId}/cancel` | 中断规则评测或 LLM Judge，并保留已有结果 |
| DELETE | `/api/v1/eval/runs/{evalRunId}` | 活跃时先中断，再级联删除整次评测 |
| GET | `/api/v1/eval/runs/{evalRunId}/results/{resultId}` | 完整题目 Trace |
| GET | `/api/v1/eval/code-tags` | 查询本地 Git Tags |
| POST | `/api/v1/eval/runs/{evalRunId}/code-version/bind` | 手动校验并绑定 Tag |
| POST | `/api/v1/eval/runs/{evalRunId}/judge` | 发起 LLM Judge |
| GET | `/api/v1/eval/judge-jobs/{judgeJobId}` | Judge 结果 |

页面删除操作都会弹出不可恢复的二次确认。中断 Run 时，已经完成的题目和得分保留，`QUEUED/RUNNING` 题目标记为 `CANCELLED`；删除 Run 会同时删除逐题结果、Judge 记录和代码版本绑定。删除数据集会同时删除其所有非活跃历史评测，若仍有关联的 `QUEUED/RUNNING/JUDGING` 任务，需先中断或删除这些任务。

## 评分边界

规则总分沿用现有 E2E100 权重：route 16%、answer 22%、step 12%、tool 14%、grounding 12%、memory 10%、stability 8%、efficiency 4%、safety 2%。

`request_tool`、`ask_user` 属于元工具：Trace 中单独计入 `metaToolCallCount`，不参与普通工具数、非执行步骤工具调用、重复调用、Grounding 或稳定性扣分。

LLM Judge 与规则评分始终分开保存和展示，不生成隐含权重的混合分。
