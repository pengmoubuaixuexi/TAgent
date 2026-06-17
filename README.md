# TAgent

<div align="center">

🚀 **A Production-Grade AI Agent Engineering Framework**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java 17+](https://img.shields.io/badge/Java-17+-orange)](https://www.oracle.com/java/)
[![Spring Boot 3.4.3](https://img.shields.io/badge/Spring%20Boot-3.4.3-green)](https://spring.io/projects/spring-boot)
[![CI](https://github.com/pengmoubuaixuexi/TAgent/actions/workflows/ci.yml/badge.svg)](https://github.com/pengmoubuaixuexi/TAgent/actions/workflows/ci.yml)
[![GitHub Stars](https://img.shields.io/github/stars/pengmoubuaixuexi/TAgent?style=social)](https://github.com/pengmoubuaixuexi/TAgent)

[🇬🇧 English](./README_EN.md) | 🇨🇳 中文

> 不只是简单的模型封装，而是覆盖 Agent 请求的完整生命周期：接入、路由、运行时装配、规划执行、RAG、记忆、MCP 工具治理、人工审批、执行中干预，到 SSE 流式输出和全链路观测。

</div>

---

## 📖 项目简介

TAgent 是一个基于 **Java 17**、**Spring Boot**、**Spring AI** 和 **DDD 分层架构** 构建的企业级 AI Agent 工程实践项目。

它覆盖了一次 Agent 请求从**接入、路由、运行时装配、规划执行、RAG、记忆、MCP 工具治理、人工审批、执行中干预**，到 **SSE 流式输出和全链路观测**的完整过程。

*本仓库是脱敏后的公开版本，不包含真实密钥、运行日志、历史对话、临时报告、数据库备份和个人文件。*

---

## 🎯 系统总览

![TAgent 端到端架构](docs/images/tagent-end-to-end-architecture-2026-06-v2.png)

### 主链路

```text
用户请求
  -> Filter / SSE Controller
  -> UnifiedAgentRouter
  -> Fixed / Auto / Flow
  -> Advisor + LLM
  -> MCP 工具执行
  -> SSE 流式响应
```

### 主链之外的关键控制链

- **动态工具补充**：路由判断缺失能力 → PgVector 从工具目录中匹配真实 MCP 工具 → 与 Agent 常驻工具合并
- **执行中干预**：用户可发送 `steer` 重做当前步骤、`answer_now` 跳过剩余步骤、`cancel` 中止执行
- **主动追问**：模型缺少关键信息时可调用 `ask_user`，通过 SSE 向用户收集补充信息，再回填继续执行

---

## ✨ 项目亮点

| 能力 | 实现 |
|---|---|
| **三种 Agent 模式** | Fixed 单步直答、Auto 分析执行闭环、Flow DAG 编排 |
| **数据库驱动装配** | Agent、Client、Model、Prompt、Advisor、RAG、MCP 关系由数据库配置 |
| **统一 Agent 路由** | 一次模型调用选择 Agent，并输出可能缺失的工具能力 |
| **动态 MCP 工具** | 工具目录中文化、意图扩写、PgVector 语义匹配、按请求临时补挂 |
| **MCP 自愈** | 懒探活、失败重试、超时重连、dead-client 重建、冷却与熔断 |
| **工具治理** | 非执行步禁工具、未知工具纠正、参数提示、轮次预算、跨 MCP 并行 |
| **Agentic RAG** | SIMPLE、HyDE、FUSION、DECOMPOSE 四种查询策略 |
| **四层记忆** | Working、Chat、Long-Term、Episodic Memory |
| **流式干预** | Auto、Flow、Fixed 均支持立即回答、引导与取消执行 |
| **主动追问** | `ask_user` 通过 SSE 向用户请求补充信息，默认关闭、按 session 限次和超时 |
| **人机协同** | 高风险工具调用通过 SSE 请求人工批准或拒绝 |
| **可解释输出** | 工具进度、RAG evidence、Memory evidence、步骤状态 |
| **全链路观测** | Prometheus、ELK、Jaeger、event_log、LLM 成本与 MCP 健康页 |

---

## 🔄 端到端请求流程

### 1. 接入与 SSE 建连

请求进入 `POST /api/v1/agent/auto_agent` 后依次经过：

1. `MdcTraceFilter`：写入 request、trace、user、tenant、session 等上下文
2. `RateLimitFilter`：按用户、会话或 IP 进行限流
3. `IdempotencyFilter`：同步请求支持幂等，SSE 长连接端点直接放行
4. `AiAgentController`：创建 `ResponseBodyEmitter`、注册审批通道并立即发送 `ack`
5. Controller 组装 `ExecuteCommandEntity`，交给 Dispatch 异步执行

`ack` 会携带干预能力是否开启，前端据此决定是否显示执行中的操作栏。

### 2. 统一路由与懒装配

`AgentDispatchService` 负责：

- 用户未指定 Agent 时，调用 `UnifiedAgentRouter` 选择最合适的 `agent_id`
- 用户已经指定 Agent 时，跳过 Agent 选择，但仍可推断缺失工具能力
- 路由结果同时返回 `missing_tool_descs`，用于后续动态补工具
- 首次使用 Agent 时通过 Armory 懒加载 ChatClient、Model、Advisor 和 MCP callback
- 从数据库读取 Agent 的 `strategy`，分派到 Fixed、Auto 或 Flow

---

## 🏃 三种执行模式

### Fixed 模式

适合直接问答或单轮工具任务：

```text
准备上下文 -> 注入 Advisor -> 合并工具 -> LLM 流式生成 -> 保存记忆 -> 输出
```

也支持 MCP 工具调用、RAG、长期记忆、`steer` 重做、`answer_now` 提前结束。

### Auto 模式

适合目标明确但需要自主分析、执行和检查的任务：

| 步骤 | 职责 | 是否开放工具 |
|---|---|---|
| Step1 | 分析任务、判断完成度 | ❌ |
| Step2 | 执行具体任务 | ✅ |
| Step3 | 质量检查与 Reflexion | ❌ |
| Step4 | 汇总最终结果 | ❌ |

Auto 会循环执行，直到任务完成或达到 `maxStep`。Step3 判断失败时，可携带结构化 critique 返回 Step2 修正。

### Flow 模式

适合可拆解、存在依赖关系的复杂任务：

| 步骤 | 职责 | 是否开放工具 |
|---|---|---|
| Step1 | 分析需要的工具能力 | ❌ |
| Step2 | 生成带 `DEPENDS_ON` 的步骤计划 | ❌ |
| Step3 | 将计划解析为 DAG | - |
| Step4 | 按依赖关系并行执行并最终整合 | ✅ |

DAG 中依赖已经满足的步骤可以并行，不同 MCP Server 的工具可以并行，同一个 MCP Server 复用同一连接时保持串行。

---

## 🛠️ 动态 MCP 工具补充

固定给每个 Agent 挂大量工具，会增加上下文、工具幻觉和选错工具的概率。TAgent 将工具拆成"常驻工具"和"按请求动态补充工具"。

```text
UnifiedAgentRouter
  -> missing_tool_descs
  -> 每条能力描述生成 embedding
  -> PgVector 查询 mcp_tool_vector
  -> 每类能力取 Top-K
  -> 相对距离阈值过滤
  -> 多类结果并集去重
  -> 懒创建或复用 MCP callback
  -> 常驻工具 + 动态工具
  -> 注入本次请求
```

工具资产由两部分组成：
- MySQL `ai_mcp_tool_catalog`：真实工具名、MCP、原始描述、中文描述和中文意图
- PgVector `mcp_tool_vector`：用于语义检索的工具向量

当前工具匹配采用 embedding-only，不回退 BM25 或 LLM rerank。向量服务不可用时宁可跳过动态补挂，也不盲目匹配无关工具。

---

## 🔧 MCP 工具治理与自动重连

MCP 调用不是直接执行裸 callback，而是经过 `MeteredToolCallback`、`RobustToolCallingManager` 和 `McpClientRegistry` 三层治理。

### McpClientRegistry

Registry 维护 MCP Client、工具与 MCP 的映射、重建工厂、最近成功时间、连续失败次数和熔断状态。

自愈路径包括：

1. 工具调用前按需进行懒探活
2. 首次调用失败后执行有限重试
3. 超时后再次探活，判断连接仍存活还是已经死亡
4. dead-client 或发送失败时重建 MCP Client
5. 重建成功后刷新旧 `MeteredToolCallback` 内部 delegate
6. 同一 MCP 设置 10 秒重连冷却，避免连续抖动和重连风暴
7. 连续失败进入熔断状态时跳过无意义重连

重连后对模型暴露的 ToolDefinition 保持稳定，因此工具参数提示不会因 callback 切换而丢失。

---

## 📚 Advisor 链

Advisor 由数据库配置，并按 order 参与请求：

| Advisor | 作用 |
|---|---|
| Semantic Cache | 相似问题命中后直接短路后续链路 |
| Long-Term Memory | 注入长期画像和语义记忆，结束后异步抽取新记忆 |
| Episodic Memory | 注入当前会话或跨会话摘要 |
| Prompt Injection | 检测潜在提示词注入 |
| PII Mask | 对输入或输出中的敏感信息进行处理 |
| Agentic RAG | 判断是否检索以及选择查询策略 |
| Rag Answer | 执行混合检索、rerank、父子文档替换和引用组装 |
| Chat Memory | 只读历史上下文，完整回答由应用层写入 |
| CoVe | 对回答声明进行检索核验与观测 |

---

## 🧠 Agentic RAG

RAG Router 会先判断是否需要检索，再从四种策略中选择：

| 策略 | 说明 |
|---|---|
| SIMPLE | 重写原始查询后直接检索 |
| HYDE | 先生成假想文档，再用其语义进行检索 |
| FUSION | 生成多个查询变体，并通过 RRF 融合结果 |
| DECOMPOSE | 将多跳问题拆成多个子查询并行检索 |

检索链路：

```text
Query Planning
  -> PgVector 语义召回
  -> Elasticsearch BM25
  -> RRF 融合
  -> Cross Encoder / LLM Rerank
  -> Child 命中替换为 Parent Document
  -> 注入编号引用
  -> rag_evidence SSE
```

---

## 💾 四层记忆

| 记忆层 | 存储 | 用途 |
|---|---|---|
| Working Memory | Redis | 保存 Auto/Flow 步骤中间产物，支持步骤间读取和断线后的完成态回放 |
| Chat Memory | MySQL + Redis Cache | 保存多轮聊天历史和滚动摘要 |
| Long-Term Memory | MySQL Meta + PgVector | 保存用户画像、技能、偏好、计划和情况，支持语义召回 |
| Episodic Memory | MySQL | 保存会话阶段性摘要和近期经历 |

长期记忆包含：
- 固定画像槽位
- 情景记忆语义召回
- exact match 与语义去重
- 单值覆盖、累加记忆合并
- 冲突判断
- 访问热度继承与冷记忆归档
- 同一轮多步骤共享召回快照

---

## 🎮 执行中干预

Auto、Flow、Fixed 三种模式均支持 `steer`、`answer_now` 和 `cancel`。

### 引导 steer

```http
POST /api/v1/agent/steer
Content-Type: application/json

{
  "sessionId": "session_demo_001",
  "idea": "请重点从工程落地和风险控制角度分析"
}
```

行为：
- 中断当前正在生成的流，而不是等待步骤自然结束
- 保留当前 reasoning、半截输出和已有步骤结果
- 将新想法合并到有效用户问题
- 重做当前步骤，并将引导继续传递到后续步骤

---

## 🤔 主动追问 ask_user

当模型缺少完成任务所必需的信息，或用户意图存在多种合理理解需要拍板时，可以调用 `ask_user` 主动向用户追问。

流程：

```text
Advisor + LLM
  -> ask_user tool_call
  -> user_input_required SSE
  -> 用户补充回填
  -> ask_user tool result
  -> 继续下一轮推理
```

设计边界：
- `agent.user-input.enabled=false` 时完全不广播 `ask_user`，默认不影响原链路
- `UserInputGate` 按 `sessionId` 限制追问次数，并支持超时自动放行
- `ask_user` 与人工审批互不复用 ID：审批管工具授权，`ask_user` 管需求澄清

---

## 👨‍💼 人工审批

高风险工具可配置为执行前审批：

1. 后端发送 `human_approval_required` SSE 事件
2. 前端显示工具名、参数和审批原因
3. 用户调用审批接口
4. 批准后执行，拒绝或超时则向模型返回结构化错误

```http
POST /api/v1/agent/approval
Content-Type: application/json

{
  "approvalId": "approval-id-from-sse",
  "approved": true
}
```

---

## 📡 SSE 事件

| 事件 | 用途 |
|---|---|
| `ack` | 确认连接建立并返回干预开关 |
| `step_start` / `step_end` | 展示 Agent 当前步骤 |
| `token` | 逐 token 渲染回答 |
| `tool_call_start` / `tool_call_end` / `tool_call_error` | 展示工具进度 |
| `human_approval_required` | 请求人工审批 |
| `user_input_required` | 模型通过 `ask_user` 请求用户补充信息 |
| `rag_evidence` | 展示知识库引用依据 |
| `memory_evidence` | 展示记忆召回依据 |
| `data` | 返回步骤结果或最终结果 |

---

## 📊 可观测性

TAgent 同时记录模型、工具和 Agent 步骤：

- `LlmObservationRecorder`：模型、耗时、Token、缓存 Token、billing scope
- `ai_event_log`：步骤输入输出、路由、工具、干预和最终结果
- Prometheus：LLM 与 MCP 指标
- ELK：结构化日志与 MDC 上下文
- Jaeger：跨 Controller、Step、LLM 和工具调用 Trace
- `WireTraceRecorder`：在不消费下游响应体的前提下记录流式 wire 信息

页面：
- 系统观测：`http://localhost:8099/observe.html`
- MCP 观测：`http://localhost:8099/observe-mcp.html`

---

## 📋 技术栈

- **Java 17**
- **Spring Boot 3.4.3**
- **Spring AI 1.1.7**
- **MCP SDK 0.18.2**
- **MyBatis、MySQL**
- **PostgreSQL + pgvector**
- **Redis**
- **Elasticsearch**
- **Resilience4j**
- **Reactor、SSE**
- **Micrometer、Prometheus、Grafana、Jaeger、ELK**

---

## 🏗️ 项目模块

| 模块 | 说明 |
|---|---|
| `ai-agent-station-study-api` | 接口、DTO 和统一响应 |
| `ai-agent-station-study-app` | Spring Boot 启动、配置、静态页面和 Mapper XML |
| `ai-agent-station-study-domain` | Agent 路由、执行策略、RAG、记忆、MCP 与安全治理 |
| `ai-agent-station-study-infrastructure` | DAO、Repository、缓存和外部存储适配 |
| `ai-agent-station-study-trigger` | HTTP Controller、管理接口和任务触发 |
| `ai-agent-station-study-types` | 通用类型、异常和任务调度组件 |
| `docs/dev-ops` | Docker、SQL 迁移、Grafana、Prometheus 和 MCP 配置 |
| `mcp-server-hmdp` / `mcp-servers` | MCP Server 示例 |

---

## 🚀 本地运行

### 外部依赖

默认地址：

| 服务 | 地址 |
|---|---|
| MySQL | `127.0.0.1:13306` |
| PostgreSQL + pgvector | `127.0.0.1:15432` |
| Redis | `127.0.0.1:16379` |
| Elasticsearch | `127.0.0.1:9200` |
| Logstash | `127.0.0.1:4560` |
| Jaeger OTLP | `127.0.0.1:4318` |

### 环境变量

```bash
# LLM 配置
LLM_BASE_URL=https://your-openai-compatible-endpoint
LLM_API_KEY=your-llm-key
LLM_MODEL=your-model

# 向量模型配置
EMBEDDING_BASE_URL=https://your-embedding-endpoint
EMBEDDING_API_KEY=your-embedding-key
EMBEDDING_MODEL=BAAI/bge-m3

# 数据库凭据
MYSQL_USERNAME=root
MYSQL_PASSWORD=your-mysql-password
PGVECTOR_USERNAME=postgres
PGVECTOR_PASSWORD=your-postgres-password
```

按需设置 MCP 和观测凭据：

```bash
GITHUB_PERSONAL_ACCESS_TOKEN=your-github-token
GRAFANA_API_KEY=your-grafana-key
CSDN_API_COOKIE=your-csdn-cookie
WEIXIN_API_APP_SECRET=your-weixin-secret
```

### 数据库迁移

增量脚本位于：

```text
docs/dev-ops/sql-migrations
```

新环境应按版本顺序执行。动态工具功能至少需要 `V041`、`V046`、`V047`。

### 构建

```bash
mvn "-Dmaven.test.skip=true" package
```

项目包含依赖外部服务和账号环境的集成测试。普通本地构建可跳过测试，验收时再运行指定测试类。

### 启动

```bash
java -jar ai-agent-station-study-app/target/ai-agent-station-study-app.jar
```

默认端口：`8099`

- 对话页：`http://localhost:8099/index.html`
- Agent 配置：`http://localhost:8099/agent-config.html`
- 健康检查：`http://localhost:8099/actuator/health`

---

## 📤 流式请求示例

```http
POST /api/v1/agent/auto_agent
Content-Type: application/json
Accept: text/event-stream
```

```json
{
  "aiAgentId": "3",
  "message": "总结当前系统的 Agent、RAG、记忆和 MCP 能力",
  "sessionId": "session_demo_001",
  "userId": "user_demo",
  "maxStep": 5
}
```

---

## ⚙️ 关键配置

```yaml
agent:
  intervention:
    enabled: true
  steer:
    enabled: true
    max-rounds: 3
  answer-now:
    finalize-tools: true
  user-input:
    enabled: false
    max-asks: 2
    timeout-seconds: 120

  mcp:
    disable-tools-on-nonexec-steps: true
    tool-call:
      max-attempts: 2
      retry-delay-ms: 1000
      parallel-enabled: true
      max-serial-rounds-per-client: 3

  dynamic-tools:
    infer-on-selected-agent: true
    per-need-top-k: 2
    max-extra-tools-per-request: 6
    match-cache-ttl-ms: 600000
```

---

## 🔒 安全说明

公开仓库中不应提交：

- API Key、Access Token、Cookie、私钥
- `.local-config`、浏览器状态和本地凭据
- 运行日志、压测报告和临时调试文件
- 个人对话、简历和面试资料
- 数据库备份和生产数据

---

## 📝 更新记录

详见 [CHANGELOG.md](CHANGELOG.md)。

---

## 📄 许可证

本项目采用 [MIT License](LICENSE) 许可证。

---

## 🤝 贡献指南

欢迎 Issue、Pull Request 和讨论！我们非常期待你的建议和贡献。

**问题或建议？** [创建 Issue](https://github.com/pengmoubuaixuexi/TAgent/issues) 或 [发起讨论](https://github.com/pengmoubuaixuexi/TAgent/discussions) 🙌

---

<div align="center">

Made with ❤️ by pengmoubuaixuexi

</div>
