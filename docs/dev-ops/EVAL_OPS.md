# EvalOps 评测平台

## 能力范围

- 可视化创建数据集、编辑题目和显式评测规则。
- 数据集使用 `DRAFT -> PUBLISHED` 版本流；发布版本不可修改，发布后自动创建下一版草稿。
- 支持 `INDEPENDENT` 独立样本和 `SCENARIO` 有状态会话场景。
- 复用正式 Agent Dispatch、RunSnapshot 和 RunEvent 链路执行题目，不再从网页启动 JUnit。
- 每题持久化答案、Agent/策略、Trace、信号、九维规则分、等级和扣分原因。
- 规则评测完成后，可选择全部、规则低分或失败题目发起独立 LLM Judge。
- LLM Judge 持久化 correctness/relevance/completeness/usefulness/safety、overall、verdict 和问题列表。
- Judge 弹窗从当前评测所有者可用的模型目录加载选项，选择模型时自动绑定对应 API；可选择与被评测 Agent 不同的模型。目录包含启用的平台模型、所有者本人的旧私有配置和个人 BYOK 连接，重复节点引用合并展示。保留私有模型的 `platform_enabled=0`，无需为 Judge 向其他用户公开。
- Judge 的模型权限与用量归属使用数据集所有者；隔离评测的 `eval-*` 用户仅用于评测运行隔离，不作为 Judge 的凭据所有者。创建任务和实际调用前均校验权限，平台辅助服务仍遵循账号设置。模型选择失效后需刷新列表重新选择，不自动替换为另一个模型。
- Judge 详情和批次列表提供“补评失败题”：只重新请求评分失败的题目，沿用该批模型、连接和 rubric，不重跑 Agent。补评新建关联批次并保留原记录；详情、导出和整批分数合并原成功结果与补评结果，不把单题分数作为整批平均。历史批次若已有更新的同模型、同连接、同 rubric 批次，需从最新批次补评。
- 普通 `SELECTED` / 低分 / 执行失败范围也只继承同一评测、模型连接和 rubric 的最近已结束批次。无完整 `ALL` 基线的子集仅展示本次均分，不覆盖 Run 的整批 Judge 分。原来的 `FAILED` 范围仍指 Agent 执行失败，与 Judge 失败补评不同。
- 每题最多发起两次评分请求（包括网络、空正文、截断及格式问题），每次都经过权限和用量校验。仅当供应商明确返回 `finish_reason=length` 时，第二次输出额度从 3000 增至 6000；不把思考正文当作评分。非重试类 HTTP 4xx、权限拒绝、预算耗尽立即停止。
- 评分必须满足五维整数 0–5、总分整数 0–100、有效评价等级、非空理由和文本问题数组；校验失败不写入成功评分。失败记录的 `rawResponse` 保存有界诊断元数据（每次请求状态、结束原因、正文/思考长度、供应商 Token 用量和请求标识），不保存请求密钥或完整思考正文。失败诊断区分 `EMPTY_CONTENT` / `OUTPUT_TRUNCATED` / `INVALID_SCHEMA` 等；对应的用量记录即使有实际 Token 消耗，outcome 仍为 `ERROR`，不能把请求有消耗等同于评分成功。
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

- 逐题 `PASS` 表示 Agent 正常执行完成，不表示答案已经通过语义质量验收。规则分、警告和 LLM Judge 分数需要分别查看；`ERROR` 表示运行异常，`TIMEOUT` 表示触及单题硬超时。

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

应用启动时会初始化 Git 仓库访问能力；每次点击发起评测时都会重新读取当前 Git 工作区，用临时 Index 和临时对象目录即时生成过滤后的 Git Tree Hash，不会修改真实 Git Index。每次运行独立保存当时的快照，保证随后修改磁盘源码不会改变历史记录，也不会误用应用启动时的旧工作区状态。

评测完成后可以先提交代码、再创建 Tag。下一次发起评测前，平台会扫描尚未处理的 Tag，用历史评测保存的忽略规则重新计算 Tag Tree Hash；完全一致时自动标记为 `AUTO_VERIFIED`。没有下一次评测时，也可以在详情页手动选择 Tag：一致为 `MANUAL_VERIFIED`，强制关联必须填写原因并标记为 `MANUAL_UNVERIFIED`。

根目录 `.evalopsignore` 独立于 `.gitignore`，默认排除日志、`target`/`build`、测试源码、临时目录、文档与历史评测报告、图片、视频、PDF、本地 `application-dev.yml`、Docker Compose 编排文件和个人研究资料，只保留 TAgent 产品源码及数据库结构。每次评测同时保存忽略规则快照，后续校验不会受到规则变更影响；旧评测仍沿用其历史规则，不会被新版范围静默改写。

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
| GET | `/api/v1/eval/runs/{evalRunId}/judge-models` | 当前评测所有者可用的 Judge 模型元数据，不返回凭据 |
| POST | `/api/v1/eval/runs/{evalRunId}/judge` | 发起 LLM Judge |
| GET | `/api/v1/eval/judge-jobs/{judgeJobId}` | Judge 结果 |
| POST | `/api/v1/eval/judge-jobs/{judgeJobId}/retry-failed` | 沿用模型，仅补评该批 Judge 失败题，并合并已有有效评分 |

页面删除操作都会弹出不可恢复的二次确认。中断 Run 时，已经完成的题目和得分保留，`QUEUED/RUNNING` 题目标记为 `CANCELLED`；删除 Run 会同时删除逐题结果、Judge 记录和代码版本绑定。删除数据集会同时删除其所有非活跃历史评测，若仍有关联的 `QUEUED/RUNNING/JUDGING` 任务，需先中断或删除这些任务。

## 轨迹保存与历史兼容读取

新轨迹使用 `eval-trace-v3-json-values`，按标准 JSON 保存完整值，不生成共享对象的 `$ref`。FLOW 依赖图的数字步骤编号也会写成合法的 JSON 对象键。

针对旧版 `eval-trace-v2-old-compatible` 的已知序列化缺陷，详情和 Judge 共用受限兼容读取：恢复缺少字段名的步骤/工具依据引用，以及 FLOW 依赖图未加引号的数字键。恢复只发生在读取时，保留原始数据库记录和历史评测成绩；页面会显示兼容读取提示。其他损坏、重复键或截断内容仍明确报错，不作为空轨迹继续 Judge 或导出完整评测包。

逐题 `PASS` 只表示执行完成。改善轨迹读取不会把历史 `ERROR` 改为 `PASS`；运行修复需要重新执行评测才能验证。

Redis 快照经 Fastjson 读回后，依赖图可能实际为含整数键的 `JSONObject`。轨迹写入统一按实际 Map 条目转换 JSON 键，拒绝转换后重名、循环引用和超限内容，保留依赖边与原始工具依据。若单题内存中的评分/轨迹构建仍失败，保存显式 `traceCompleteness=INCOMPLETE` 的 ERROR 记录及已有回答，质量分留空，再执行后续题目；不把缺失证据送入 Judge。数据库持久化失败仍中止批次，避免假装已经保存。

后台评测产生的会话可能没有前台入口的归属登记。读取时仅在 MySQL 消息与运行历史归属一致、且属于当前登录用户时兼容访问；不能凭 `eval-` 前缀或管理员身份越过其他用户的权限。Redis 执行快照不可用时，页面可用本人持久化历史回放已有诊断；旧计划只读。

即使评测在写入聊天消息前失败或取消，会话页也通过 `/api/v1/history/session/{sessionId}` 独立发现本人持久运行记录，分页显示状态与错误，展开后读取已保存的执行过程。不需要重跑评测或重建历史数据。归属查询已兼容旧聊天表与新运行历史表的不同 MySQL 排序规则；验证须同时包含真实 MySQL 和零 ChatMemory 页面场景，H2 的 MySQL 模式不能替代该兼容性验证。

模型返回仅思考、没有回答正文时，在没有开始调用工具、没有输出正文且配置允许重试的条件下，最多补试一次，仍受原运行预算约束。流式 HTTP 200 中携带的提供商 `error`（如 429）会作为明确失败传播；不会再仅显示为空回答。此恢复不替代提供商配额，也不改变 RAG 的超时设置。

## 评分边界

规则总分沿用现有 E2E100 权重：route 16%、answer 22%、step 12%、tool 14%、grounding 12%、memory 10%、stability 8%、efficiency 4%、safety 2%。

`request_tool`、`ask_user` 属于元工具：Trace 中单独计入 `metaToolCallCount`，不参与普通工具数、非执行步骤工具调用、重复调用、Grounding 或稳定性扣分。

LLM Judge 与规则评分始终分开保存和展示，不生成隐含权重的混合分。
