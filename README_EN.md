# TAgent

<div align="center">

🚀 **A production-oriented AI Agent engineering reference for Java developers**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java 17+](https://img.shields.io/badge/Java-17+-orange)](https://www.oracle.com/java/)
[![Spring Boot 3.4.3](https://img.shields.io/badge/Spring%20Boot-3.4.3-green)](https://spring.io/projects/spring-boot)
[![Spring AI 1.1.7](https://img.shields.io/badge/Spring%20AI-1.1.7-6DB33F)](https://spring.io/projects/spring-ai)
[![CI](https://github.com/pengmoubuaixuexi/TAgent/actions/workflows/ci.yml/badge.svg)](https://github.com/pengmoubuaixuexi/TAgent/actions/workflows/ci.yml)
[![GitHub Stars](https://img.shields.io/github/stars/pengmoubuaixuexi/TAgent?style=social)](https://github.com/pengmoubuaixuexi/TAgent)

[中文文档](./README.md) | **English**

[![Watch the Dynamic MCP Demo](https://img.shields.io/badge/Watch-Dynamic%20MCP%20demo-0ea5e9?style=for-the-badge&logo=github)](./docs/demo-videos/03-%E6%89%A7%E8%A1%8C%E6%9C%9F%E5%8A%A8%E6%80%81%E8%A1%A5%E6%8C%82%E5%B7%A5%E5%85%B7.webm)

</div>

TAgent is an enterprise-level AI Agent implementation project built with **Java 17**, **Spring Boot**, **Spring AI**, and a **Domain-Driven Design (DDD)** layered architecture.

It is not another one-call model wrapper. TAgent makes the complete Agent-request lifecycle concrete and inspectable: ingestion, routing, runtime assembly, planning and execution, RAG, memory, MCP tool governance, human approval, in-execution intervention, SSE streaming, recovery, and end-to-end observability.

[Watch the Dynamic MCP demo](./docs/demo-videos/03-%E6%89%A7%E8%A1%8C%E6%9C%9F%E5%8A%A8%E6%80%81%E8%A1%A5%E6%8C%82%E5%B7%A5%E5%85%B7.webm). The current v2.0 architecture is shown below.

**What you can verify in the demo:** the model detects a missing capability during execution, calls `request_tool`, matches a real MCP tool through PgVector, and continues the same run with the newly attached tool. See the [full demo catalogue](./docs/demo-videos/README.md) for planning review, human approval, step-level redo, resilient runs, multimodal input, and background tasks.

*This repository is a sanitized public version without real API keys, runtime logs, chat histories, temporary reports, database backups, or personal data.*

> **🌐 Try TAgent: [https://tagentapp.cn](https://tagentapp.cn)**
>
> After registering, you can try preconfigured Agents or open “My Agents” to build Fixed, Auto, and Flow Agents. Configure System Prompts, MCP connections, and Advisors per node, and connect your own RAG knowledge base. Personal Agent configuration, knowledge retrieval, and usage observations are scoped to each user.
>
> The service is currently a trial, with platform-provided models primarily `mimo-v2.6-flash` and `deepseek-v4-flash`, subject to platform allowances. **v2.0.0 supports personal OpenAI-compatible model connections** with encrypted API keys. Platform helpers such as routing, memory summaries, and embeddings require separate consent and do not reuse a personal chat-model key. Personal model and MCP endpoints follow the operator's domain allowlists. See [Agent workspaces (Chinese)](docs/dev-ops/USER_AGENT_WORKSPACE.md) and [model connections](docs/dev-ops/MODEL_CONNECTIONS.md).

## 🆕 v2.0.0: Configure, Run, and Keep the Result

- **Personal Agents and models:** configure mode-specific nodes from public Agents, keep drafts separate from enabled versions, compare versions, restore to a draft, and debug individual nodes. Personal model credentials are encrypted and owner-scoped.
- **Maintainable knowledge bases:** inspect ingestion stages, failures, and chunks; retry, cancel, delete, and test retrieval. Normal retrieval includes only successfully published files.
- **Traceable execution:** durable history records questions, answers, configuration versions, status, and bounded diagnostics. Reconnect restores dependencies and tool cards; completion/failure notifications work away from the active conversation, with mark-all-read in the inbox.
- **Downloadable artifacts:** built-in `create_artifact` creates Markdown, CSV tables, and individual source-code files with previews, downloads, immutable revisions, and explicit version reuse. Code is saved, not executed.
- **Feedback into evaluation:** opt in to converting feedback into an EvalOps draft, review and enable it, then compare rule scores and LLM Judge results. A model's original answer does not automatically become the reference answer.
- **Runtime and usage control:** shared run budgets, context shaping, selective tool-result reads, MCP capacity/idle reclamation, and a persistent per-user allowance/usage ledger. Unknown monetary pricing is not presented as an actual bill.

Shared navigation connects models, Agents, knowledge, history, artifacts/feedback, usage, and MCP observability. See the [v2.0.0 upgrade and feature guide](docs/dev-ops/V2_UPGRADE.md).

### Who Is This For?

- Developers learning **Java / Spring AI Agent engineering** beyond a simple model wrapper.
- Builders exploring **MCP tool governance, dynamic tool attachment, Agentic RAG, and multi-layer memory**.
- Teams looking for a reference implementation of **SSE streaming, human approval, in-execution intervention, proactive `ask_user` clarification, and observability**.

---

## 🎯 System Overview

![TAgent 2.0 End-to-End Architecture](docs/images/tagent-end-to-end-architecture-v2.0-2026-10.png)

[Full-resolution image](docs/images/tagent-end-to-end-architecture-v2.0-2026-10.png) · [Editable SVG](docs/images/tagent-end-to-end-architecture-v2.0-2026-10.svg)

## 🎬 Feature Demos

The repository includes focused recordings for the features below. Some were recorded on earlier releases; v2.0.0 interfaces and stage permissions have since changed:

- [Fixed / Auto / Flow execution modes](./docs/demo-videos/01-%E4%B8%89%E7%A7%8D%E7%AD%96%E7%95%A5%E5%9F%BA%E7%A1%80%E9%97%AE%E7%AD%94.mp4)
- [Dynamic MCP tool attachment during execution](./docs/demo-videos/03-%E6%89%A7%E8%A1%8C%E6%9C%9F%E5%8A%A8%E6%80%81%E8%A1%A5%E6%8C%82%E5%B7%A5%E5%85%B7.webm)
- [Flow plan review and editing](./docs/demo-videos/05-%E6%B5%81%E7%A8%8B%E8%AE%A1%E5%88%92%E7%A1%AE%E8%AE%A4%E7%BC%96%E8%BE%91.webm)
- [Human approval for high-risk tools](./docs/demo-videos/08-%E9%AB%98%E5%8D%B1%E5%B7%A5%E5%85%B7%E4%BA%BA%E5%B7%A5%E5%AE%A1%E6%89%B9.mp4)
- [Step-level redo by run ID](./docs/demo-videos/09-%E8%BF%90%E8%A1%8C%E7%BC%96%E5%8F%B7%E6%AD%A5%E9%AA%A4%E9%87%8D%E5%81%9A.mp4)
- [Disconnect, reconnect, and continue in the background](./docs/demo-videos/12-%E6%96%AD%E7%BA%BF%E9%87%8D%E8%BF%9E%E5%90%8E%E5%8F%B0%E7%BB%AD%E4%BD%9C.mp4)
- [Multimodal image understanding](./docs/demo-videos/13-%E5%9B%BE%E7%89%87%E7%90%86%E8%A7%A3%E8%83%BD%E5%8A%9B.mp4)
- [Scheduled tasks and file-change monitoring](./docs/demo-videos/14-%E5%AE%9A%E6%97%B6%E4%BB%BB%E5%8A%A1%E5%92%8C%E5%AF%B9%E8%B1%A1%E7%9B%91%E8%A7%86%E5%99%A8%E5%8A%9F%E8%83%BD.mp4)

### Main Request Flow

```text
User Request
  -> Filter / SSE Controller
  -> UnifiedAgentRouter
  -> Fixed / Auto / Flow
  -> Advisor + LLM
  -> MCP Tool Execution
  -> SSE Streaming Response
```

### Key Control Flows Beyond Main Chain

- **Dynamic Tool Supplement**: Router detects missing capabilities → PgVector matches real MCP tools from catalog → merge with Agent's resident tools
- **Flow Plan Review**: Pause after a plan has been parsed into a DAG; the user can inspect, edit, and confirm it before execution
- **Run Snapshots and Redo**: Save run steps in Redis and resume from `/runId-stepN` with a correction
- **Resilient Runs**: Refreshing the browser or reconnecting SSE does not cancel backend work; the timeline is recovered on reconnect
- **Background Tasks**: Draft, confirm, schedule, pause, resume, or run one-time, Cron, and stable-file-change tasks
- **In-Execution Intervention**: Users can send `steer` to redo current step, `answer_now` to skip remaining steps, or `cancel` to abort
- **Proactive Clarification**: Model can invoke `ask_user` to collect missing information via SSE, then continue execution with user input

---

## ✨ Key Capabilities

| Capability | Implementation |
|---|---|
| **Three Agent Modes** | Fixed (configured sequential Client chain), Auto (analyze-execute loop), Flow (DAG orchestration) |
| **Personal Agent Workspace** | Mode-specific nodes, model/Prompt/MCP/Advisor settings, drafts, enabled versions, comparisons, and node debugging |
| **Personal Models and Allowances** | Encrypted OpenAI-compatible API keys, separate platform-helper consent, persistent allowances and known/unknown usage |
| **Knowledge Management** | Ingestion progress, resumable retry, chunk previews, retrieval tests, per-user quotas and publication filtering |
| **Artifacts and Feedback** | Versioned Markdown, CSV, and code downloads; opt-in feedback-to-evaluation drafts |
| **Database-Driven Assembly** | Agent, Client, Model, Prompt, Advisor, RAG, MCP relationships configured via DB |
| **Unified Agent Router** | Single LLM call to select Agent and infer missing tool capabilities |
| **Dynamic MCP Tools** | Tool catalog localization, intent expansion, PgVector semantic matching, on-demand attachment |
| **MCP Self-Healing** | Lazy probing, failure retry, timeout reconnect, dead-client rebuild, cooldown & circuit breaker |
| **Tool Governance** | Separate catalog access, meta-tools, and business calls by stage; relevance filters, context/run budgets, side-effect-aware retries |
| **Flow Plan Review** | Pause after DAG parsing; users can inspect, edit, and confirm the plan before it runs |
| **Run Snapshots & Step-level Redo** | Redis TTL snapshots, `/run` history, and `/runId-stepN` targeted redo |
| **Resilient Runs & Timeline Reconnect** | Snapshot-first recovery, Redis Stream catch-up, then live SSE subscription |
| **Background Task Center** | One-time schedules, Spring Cron, stable-file-change monitoring, confirmation, lifecycle controls, and run history |
| **Multimodal Image Messages** | URLs, uploads, and clipboard images are normalized to OSS and preserved as `text + image` chat memory |
| **Agentic RAG** | Four query strategies: SIMPLE, HyDE, FUSION, DECOMPOSE |
| **Four-Layer Memory** | Working, Chat, Long-Term, Episodic Memory |
| **Streaming Intervention** | Auto/Flow/Fixed support immediate answer, steering, or cancellation |
| **Proactive Q&A** | `ask_user` requests supplementary info via SSE (disabled by default, per-session limit & timeout) |
| **Human-in-the-Loop** | High-risk tool calls request manual approval/rejection via SSE |
| **Explainable Output** | Tool progress, RAG evidence, Memory evidence, step status |
| **Full-Chain Observability** | Prometheus, ELK, Jaeger, event_log, personal model usage and MCP health; site statistics remain admin-only |
| **Notification Inbox** | Clarifications, approvals, plan review, completion/failure alerts, and mark-all-read; reading does not approve an action |

---

## 🔄 End-to-End Request Flow

### 1. Ingestion & SSE Connection

Request enters `POST /api/v1/agent/auto_agent` through:

1. `MdcTraceFilter`: Inject context (request, trace, user, tenant, session)
2. `RateLimitFilter`: Rate limiting by user/session/IP
3. `IdempotencyFilter`: Idempotency support for sync requests
4. `AiAgentController`: Create `ResponseBodyEmitter`, register approval channel, send `ack`
5. Dispatch async execution via `ExecuteCommandEntity`

The `ack` includes intervention capability flags for frontend UI rendering.

### 2. Unified Router & Lazy Assembly

`AgentDispatchService` handles:

- When no Agent is specified, select the best `agent_id` through `UnifiedAgentRouter`
- When an Agent is pre-selected, optionally continue inferring missing capabilities via `agent.dynamic-tools.infer-on-selected-agent`
- Return `missing_tool_descs` for router-stage dynamic attachment
- Let the model call `request_tool` during execution when its current tools are insufficient
- Lazy-load ChatClient, Model, Advisor, MCP callback on first use via Armory
- Dispatch to Fixed, Auto, or Flow strategy based on `agent.strategy`

Assembly relationships sourced from:
- `ai_agent`, `ai_client`, `ai_client_model`, `ai_client_api`
- `ai_client_system_prompt`, `ai_client_advisor`, `ai_client_tool_mcp`, `ai_client_rag_order`

Armory caches assembled runtime components. Workspace updates use drafts and enabled versions, invalidate affected components, and bind each run to its configuration version; editing a draft does not silently replace an active run's configuration.

### 3. Three Execution Modes

#### Fixed Mode
Runs the configured Client chain sequentially; a single-node setup suits direct Q&A or tool tasks:
```text
Prepare Context -> Inject Advisor -> Merge Tools -> LLM Stream -> Save Memory -> Output
```
Also supports MCP calls, RAG, long-term memory, `steer` re-generation, and `answer_now` early exit.

#### Auto Mode
Self-driven analysis, execution, and verification loop:

| Step | Responsibility | Business MCP Calls |
|---|---|---|
| Step1 | Analyze task, judge completion | ❌ |
| Step2 | Execute specific task | ✅ |
| Step3 | Quality check & Reflexion | ❌ |
| Step4 | Summarize final result | ❌ |

Loops until task complete or `maxStep` reached. Step3 can return structured critique to refine Step2.

#### Flow Mode
Decompose complex tasks into dependency-aware steps:

| Step | Responsibility | Business MCP Calls |
|---|---|---|
| Step1 | Read the execution nodes' authorized catalog and analyze capabilities | ❌ |
| Step2 | Generate step plan with `DEPENDS_ON` | ❌ |
| Step3 | Parse plan into DAG | - |
| Step4 | Execute in parallel respecting dependencies | ✅ |

Analysis and planning read names and descriptions from the execution nodes' authorized MCP catalog without connecting or loading every tool. `request_tool` and business calls are deferred to execution. `ask_user` follows stage permissions; Auto final summaries and Flow final synthesis may use built-in `create_artifact` without enabling business MCP calls.

Parallel boundaries:
- Steps with satisfied dependencies can run concurrently
- Tools from different MCP servers can parallelize
- Same MCP server reuses connection → serial execution to avoid transport layer concurrency issues

Flow can enable **plan review**: Step2 produces a plan and Step3 parses it into a DAG, then execution pauses. The user can confirm the plan as-is or edit a step's title, content, and dependencies before Step4 runs. This is a Flow-only control and does not change the default direct execution of Fixed or Auto.

---

## 🔁 Run Snapshots and Step-level Redo

Every Agent run has a `runId` that connects its SSE events, chat memory, event log, and Redis snapshot.

- **Snapshot scope:** Fixed saves the response; Auto saves Step1–Step4; Flow saves planning and Step4 DAG execution steps.
- **History entry point:** `/run` lists recent run summaries for the current session.
- **Targeted redo:** `/runId-stepN correction` reuses the source Agent and prior steps, then regenerates from the requested step.
- **Retention boundary:** redo depends on a valid Redis snapshot. Durable MySQL `ai_run_history` independently preserves the owner's question, answer, Agent version, status, and bounded diagnostics, including failed/cancelled evaluation sessions. Process interruption does not automatically replay external actions.

---

## 🔌 Resilient Runs and Timeline Reconnect

A run belongs to a session, not to one browser's SSE connection. Refreshing the page, transient network failure, or switching conversations does not cancel backend execution; only an explicit cancel ends the target run.

Reconnect restores the UI in three stages: load the aggregated Timeline Snapshot, read Redis Stream events after the snapshot cursor, then subscribe to live events. Completed runs can still restore step text, tool cards, RAG and memory evidence, approvals, and clarification cards.

---

## ⏰ Background Tasks and Object Monitoring

`BackgroundTaskCommandRouter` recognizes task commands before normal Agent intent routing, without changing Fixed, Auto, or Flow responsibilities. Supported task types are:

- `SCHEDULE_ONCE`: trigger once at a specified time.
- `CRON`: trigger on a six-field Spring Cron expression in the selected time zone.
- `FILE_CHANGE_STABLE`: trigger only when a file's content changes and then remains unchanged for the specified quiet window.

New tasks are saved as drafts and require user confirmation before activation. The task center supports editing, pausing, resuming, running now, cancelling, viewing trigger history, and opening the target conversation. A trigger reuses the ordinary Agent-run path; only one run may execute per session at a time.

---

## 🛠️ Dynamic MCP Tool Supplement

Instead of loading all tools upfront (which increases context size, tool hallucination, and wrong-tool selection), TAgent separates "resident tools" from "on-demand dynamic tools". It supports two complementary paths: the router can infer missing capabilities before execution, or the model can call the `request_tool` meta-tool while executing.

```text
UnifiedAgentRouter
  -> missing_tool_descs (router-stage inference)
  or Advisor / LLM
  -> request_tool(needs) (execution-stage attachment)
  -> Prefer explicit tool names in the authorized catalog
  -> Generate embedding for remaining capabilities
  -> PgVector query mcp_tool_vector
  -> Top-K per capability
  -> Similarity and name/description checks; do not fill Top-K with weak matches
  -> Union & deduplicate results
  -> Lazy-create or reuse MCP callback
  -> Resident tools + Dynamic tools
  -> Inject into request
```

Tool assets:
- MySQL `ai_mcp_tool_catalog`: Real tool name, MCP, original desc, Chinese desc, Chinese intent
- PgVector `mcp_tool_vector`: Embedding for semantic retrieval

Users bind **MCP connections** in the workspace; concrete tools are loaded as execution needs them. Flow analysis/planning read the execution nodes' authorized capability catalog and distinguish bound, loaded, and executed tools. These stages do not expose `request_tool`.

During execution, explicit tool names take priority, followed by vector similarity corroborated by names/descriptions. Weak candidates do not fill a quota. If vector search is unavailable, matching may fall back to names/descriptions within the authorized catalog; no suitable match is reported explicitly. MCP bindings and public-catalog permissions still constrain the search. Seeing a catalog entry does not prove connectivity or execution.

---

## 🔧 MCP Tool Governance & Auto-Reconnect

Tool calls go through `MeteredToolCallback`, `RobustToolCallingManager`, and `McpClientRegistry` three-layer governance.

### McpClientRegistry

Maintains MCP Client, tool-MCP mapping, rebuild factory, last-success time, consecutive failures, and circuit state.

Self-healing paths:
1. Lazy probing before tool calls
2. Limited retry on first failure
3. Timeout re-probe to check if connection is truly dead
4. Rebuild MCP Client on dead-client or send failure
5. After rebuild, refresh old `MeteredToolCallback` internal delegate
6. 10-second cooldown per MCP to avoid retry storms
7. Circuit open when consecutive failures accumulate

Reconnected state keeps `ToolDefinition` stable, preserving parameter hints. The pool also enforces global/per-owner capacity, active leases, and idle reclamation. Retry decisions account for side effects and outcome certainty; uncertain writes are not automatically replayed.

### MeteredToolCallback

Each tool call handles:
- Input normalization & parameter constraint hints
- First-failure, retry, recovery, final-failure stats
- Timeout probing & reconnection
- High-risk tool approval
- Result cleansing/compression with owner/run-scoped `resultRef` storage and selective `inspect_tool_result` reads
- `tool_call_start`/`tool_call_end`/`tool_call_error` SSE events
- Process-level send lock per MCP Server

### RobustToolCallingManager

Tool loop layer handles:
- Hide real tools schema in non-execution steps
- Tool name case correction
- Unknown tool returns available tools list for self-correction
- Single Client serial round limit
- Same-round different MCP parallelization
- Response reorder & merge

### MCP Observability

Visit `http://localhost:8099/observe-mcp.html` to view:
- Client state: `alive`, `dead`, `reconnect_cooldown`, `circuit_open`
- Recent success/probe/reconnect timestamps
- First-failure, recovery, retry, reconnect-failure, cooldown-hit counters
- Tool call count, latency, error rate, recent error samples

---

## 📚 Advisor Chain

Advisors are DB-configured and participate in request chain by `order`:

| Advisor | Purpose |
|---|---|
| Semantic Cache | Short-circuit if similar question cached |
| Long-Term Memory | Inject user profile & semantic memory; async extract post-response |
| Episodic Memory | Inject current or cross-session summaries |
| Prompt Injection | Detect potential prompt injection attacks |
| PII Mask | Redact sensitive info in input/output |
| Agentic RAG | Decide whether to retrieve; select query strategy |
| Rag Answer | Execute hybrid retrieval, rerank, parent-doc swap, cite assembly |
| Chat Memory | Read-only history context; full response written by application layer |
| CoVe | Retrieve-augmented verification & observability on response claims |

Custom Advisors preserve request options during Prompt rebuild and propagate trusted run context across reactive threads. Final input shaping preserves user constraints, source references, and complete assistant/tool pairs while respecting the shared run budget.

---

## 🧠 Agentic RAG

RAG Router first decides whether retrieval is needed, then selects from four strategies:

| Strategy | Description |
|---|---|
| SIMPLE | Rewrite original query, then retrieve |
| HYDE | Generate hypothetical document first, then retrieve by its embedding |
| FUSION | Generate multiple query variants, fuse results with RRF |
| DECOMPOSE | Decompose multi-hop question into parallel sub-queries |

Retrieval pipeline:
```text
Query Planning
  -> PgVector semantic recall
  -> Elasticsearch BM25
  -> RRF fusion
  -> Cross Encoder / LLM Rerank
  -> Child hit swap to Parent Document
  -> Inject numbered citations
  -> rag_evidence SSE
```

Ingestion pipeline:

```text
Tika parsing -> parent blocks (heading / paragraph / sentence boundaries) -> child chunks
  -> one small-model call per parent: a short title plus retrieval context for every child (or a rule mode without model calls)
  -> parents are written concurrently to Elasticsearch and PgVector; the parent text is stored last as the completion marker
  -> the file is published, and only then used for retrieval
```

A retry skips fully written parents. Ingestion has its own budget, and each user has limits on files, storage, daily ingestions, and queued tasks (administrators are exempt).

Dynamic tool matching and RAG document retrieval are separate pipelines:
- Dynamic tools: authorized exact-name matching, PgVector candidates with relevance checks, and bounded name/description fallback
- RAG documents: PgVector + BM25 + RRF + rerank

---

## 💾 Four-Layer Memory

| Memory Layer | Storage | Purpose |
|---|---|---|
| Working Memory | Redis | Save Auto/Flow step intermediates; support inter-step reads, snapshots, and post-disconnect replay |
| Chat Memory | MySQL + Redis Cache | Multi-turn chat history & rolling summarization |
| Long-Term Memory | MySQL Meta + PgVector | User profile, skills, preferences, plans, context; semantic recall |
| Episodic Memory | MySQL | Session-phase summaries and recent experiences |

Long-Term Memory includes:
- Fixed profile slots
- Episodic memory semantic recall
- Exact-match & semantic dedup
- Single-value override & cumulative merge
- Conflict detection
- Access hotness inheritance & cold-memory archival
- Single-round multi-step recall snapshot sharing

When RAG or memory truly retrieves content, frontend gets `rag_evidence` or `memory_evidence` SSE event.

## 🖼️ Multimodal Image Messages

Image URLs, selected files, and clipboard images follow one normalization path: TAgent persists the image to OSS, stores it in ChatMemory as `text + image`, and only sends image content to models that support it. This keeps later history replay and model capability checks consistent.

---

## 🎮 In-Execution Intervention

All three modes (Auto, Flow, Fixed) support `steer`, `answer_now`, and `cancel`. Frontend button toggles to cancel state; steering reuses main input box, no separate popup.

### Steering (steer)

```http
POST /api/v1/agent/steer
Content-Type: application/json

{
  "sessionId": "session_demo_001",
  "idea": "Focus on engineering feasibility and risk control"
}
```

Behavior:
- Interrupt current stream (don't wait for natural step end)
- Keep current reasoning, half-streamed output, prior step results
- Merge new idea into effective user query
- Re-do current step, carry steering to subsequent steps
- `agent.steer.max-rounds` limits re-do cycles per step

Flow in Step4 DAG ignores new steer to avoid cutting off launched parallel tasks.

### Immediate Answer (answer_now)

```http
POST /api/v1/agent/answer_now
Content-Type: application/json

{
  "sessionId": "session_demo_001"
}
```

Behavior:
- Interrupt current stream
- Skip remaining analysis/planning steps
- Finalize based on intermediate results & half-streamed output
- Keep necessary tools for final fact checks
- Inject no-thinking param for models supporting reasoning shutdown
- Normal memory save (Chat, Long-Term, Episodic)

Finalize steps:
- Auto: `step4_answer_now`
- Flow: `flow_step4_answer_now`
- Fixed: `fixed_answer_now`

### Cancel Execution (cancel)

```http
POST /api/v1/agent/cancel
Content-Type: application/json

{
  "sessionId": "session_demo_001"
}
```

Behavior:
- Broadcast cancel signal to Fixed, Auto, Flow strategies
- In-flight LLM stream terminates quickly via cancel trigger
- Cancelled execution skips remaining steps & finalize
- Frontend aborts local fetch after `/cancel` call to sync backend state

---

## 🤔 Proactive Clarification (ask_user)

When model lacks critical info or user intent has multiple valid interpretations, invoke `ask_user` to proactively request clarification.

Flow:
```text
Advisor + LLM
  -> ask_user tool_call
  -> user_input_required SSE
  -> User provides supplementary input
  -> ask_user result + shared context for the current user/run
  -> Continue next reasoning round
```

Design boundaries:
- `agent.user-input.enabled=false` completely suppresses `ask_user` broadcast (default preserves original flow)
- `UserInputGate` limits asks per `sessionId` & supports auto-release on timeout
- `ask_user` and human approval use separate ID channels (approval gates tool usage, ask_user gates requirement clarification)
- Clarification answers enter the current user/run's shared context for later nodes; they do not automatically become global or cross-run long-term memory
- Stage permissions distinguish clarification from real business tool execution

---

## 👨‍💼 Human Approval

High-risk tools can be configured to require pre-execution approval:

1. Backend sends `human_approval_required` SSE event
2. Frontend displays tool name, parameters, approval reason
3. User calls approval endpoint
4. Approve → execute; reject/timeout → return structured error to model

```http
POST /api/v1/agent/approval
Content-Type: application/json

{
  "approvalId": "approval-id-from-sse",
  "approved": true
}
```

---

## 📡 SSE Events

| Event | Purpose |
|---|---|
| `ack` | Confirm connection & return intervention flags |
| `step_start` / `step_end` | Show Agent's current step |
| `token` | Stream token for real-time rendering |
| `tool_call_start` / `tool_call_end` / `tool_call_error` | Show tool progress |
| `human_approval_required` | Request manual tool approval |
| `user_input_required` | Model requests supplementary info via `ask_user` |
| `rag_evidence` | Show knowledge base citation basis |
| `memory_evidence` | Show memory recall basis |
| `data` | Return step result or final result |

---

## 📦 Files and Feedback

Ask, for example: “Save this itinerary as a downloadable Markdown document and create a CSV budget table; mark information we could not verify.” Built-in `create_artifact` accepts complete Markdown, structured table columns/rows, or a single source-code file without a filesystem MCP. After a successful database save, the chat card offers preview/download and the artifacts page exposes revisions for explicit reuse.

Supported outputs are `.md`, `.csv`, and individual code files downloaded with their filenames. Code is stored and previewed as text, not executed or claimed to be compiled. Word, PDF, Excel workbooks, and multi-file ZIP output are not supported. Invalid arguments receive one repair opportunity; persistent failure falls back to visible text and explicitly reports that no file was saved.

Feedback is tied to a specific run and Agent version. Users can opt in to creating a disabled EvalOps draft, review it, and enable it for evaluation. LLM Judge may use a different model the user is authorized to access; provider permissions, auxiliary-service consent, and budgets still apply.

---

## 📊 Observability

TAgent records model, tool, and Agent step metrics:

- `LlmObservationRecorder`: Model, latency, tokens, cache tokens, billing scope
- `ai_event_log`: Step input/output, routing, tools, intervention, final result
- Prometheus: LLM & MCP metrics
- ELK: Structured logs & MDC context
- Jaeger: Cross-service trace (Controller → Step → LLM → Tool)
- `WireTraceRecorder`: Stream wire info without consuming response body

Dashboard pages:
- LLM Observability: `http://localhost:8099/observe.html`
- Personal Usage and Allowances: `http://localhost:8099/usage.html`
- Model Connections: `http://localhost:8099/models.html`
- Knowledge: `http://localhost:8099/knowledge.html`
- Run History: `http://localhost:8099/history.html`
- Artifacts and Feedback: `http://localhost:8099/outcomes.html`
- MCP Observability: `http://localhost:8099/observe-mcp.html`
- My Agents: `http://localhost:8099/user-agents.html`
- Website Traffic and Registrations (admin): `http://localhost:8099/admin-stats.html`

---

## 📋 Tech Stack

- **Java 17**
- **Spring Boot 3.4.3**
- **Spring AI 1.1.7**
- **MCP SDK 0.18.2**
- **MyBatis, MySQL**
- **PostgreSQL + pgvector**
- **Redis**
- **Elasticsearch**
- **Resilience4j**
- **Reactor, SSE**
- **Micrometer, Prometheus, Grafana, Jaeger, ELK**

---

## 🏗️ Project Modules

| Module | Description |
|---|---|
| `ai-agent-station-study-api` | Interfaces, DTOs, unified response |
| `ai-agent-station-study-app` | Spring Boot starter, config, static pages, MyBatis XML |
| `ai-agent-station-study-domain` | Agent router, execution strategies, RAG, memory, MCP, security governance |
| `ai-agent-station-study-infrastructure` | DAO, Repository, cache, external storage adapter |
| `ai-agent-station-study-trigger` | HTTP Controller, admin interface, task trigger |
| `ai-agent-station-study-types` | Common types, exceptions, scheduling components |
| `docs/dev-ops` | Docker, SQL migrations, Grafana, Prometheus, MCP config |
| `mcp-server-hmdp` / `mcp-servers` | MCP Server examples |

---

## 🚀 Quick Start

See the [single-host deployment guide (Chinese)](docs/dev-ops/server/README.md) and [v2.0.0 upgrade guide](docs/dev-ops/V2_UPGRADE.md). **Upgrading from a V063 database to v2.0.0 requires V064–V069 in order**: personal models, Agent versions, knowledge ingestion, durable history, usage accounting, and artifacts/feedback. Earlier installations must first verify V061–V063, administrator ownership, and sharing scope. Do not rerun applied migrations.

Back up the old JAR/image, configuration, MySQL, existing vector/search data, and application file volumes; follow the deployment guide's maintenance procedure before migrating and starting the new version. Keep `MODEL_SECRET_MASTER_KEY` stable: it is a Base64-encoded random 32-byte encryption key, backed up separately from the database and never regenerated on restart/upgrade. Without it, platform models remain available but personal credentials are disabled. See [model connections](docs/dev-ops/MODEL_CONNECTIONS.md), [usage rules](docs/dev-ops/MODEL_USAGE.md), [knowledge ingestion (Chinese)](docs/dev-ops/KNOWLEDGE_INGESTION.md), and [run history (Chinese)](docs/dev-ops/RUN_HISTORY.md).

### Prerequisites

Default addresses:

| Service | Address |
|---|---|
| MySQL | `127.0.0.1:13306` |
| PostgreSQL + pgvector | `127.0.0.1:15432` |
| Redis | `127.0.0.1:16379` |
| Elasticsearch | `127.0.0.1:9200` |
| Logstash | `127.0.0.1:4560` |
| Jaeger OTLP | `127.0.0.1:4318` |

### Environment Variables

```bash
# LLM Configuration
LLM_BASE_URL=https://your-openai-compatible-endpoint
LLM_API_KEY=your-llm-key
LLM_MODEL=your-model

# Embedding Configuration
EMBEDDING_BASE_URL=https://your-embedding-endpoint
EMBEDDING_API_KEY=your-embedding-key
EMBEDDING_MODEL=BAAI/bge-m3

# Optional: proxy for remote image downloads
TAGENT_IMAGE_PROXY_URL=http://127.0.0.1:7897

# Optional: private OSS storage for images
TAGENT_OSS_ENABLED=false
TAGENT_OSS_BUCKET=your-private-bucket

# Database Credentials
MYSQL_USERNAME=root
MYSQL_PASSWORD=your-mysql-password
PGVECTOR_USERNAME=postgres
PGVECTOR_PASSWORD=your-postgres-password
```

Optional MCP and observability credentials:

```bash
GITHUB_PERSONAL_ACCESS_TOKEN=your-github-token
GRAFANA_API_KEY=your-grafana-key
CSDN_API_COOKIE=your-csdn-cookie
WEIXIN_API_APP_SECRET=your-weixin-secret
```

### Database Migration

Incremental scripts located in:

```text
docs/dev-ops/sql-migrations
```

Initialize a new database from the baseline described in the deployment guide, then inspect and apply only missing incremental migrations through `V069`. Do not blindly replay every script on existing tables; MySQL DDL cannot roll back a whole migration transactionally.

### Build

```bash
mvn "-Dmaven.test.skip=true" package
```

Project includes integration tests dependent on external services. Skip tests for local build, run specific test classes during acceptance testing.

### Run

```bash
java -jar ai-agent-station-study-app/target/ai-agent-station-study-app.jar
```

Default port: `8099`

- Chat UI: `http://localhost:8099/index.html`
- Agent Config: `http://localhost:8099/agent-config.html`
- Health Check: `http://localhost:8099/actuator/health`

---

## 📤 Example Streaming Request

Starting with v1.27.0, authenticate first and send the session cookie. POST requests also require the token from `/api/v1/auth/csrf`, using its returned `headerName`. The server derives user identity from the session, not the supplied `userId`. The browser client handles this through `auth.js`.

```http
POST /api/v1/agent/auto_agent
Content-Type: application/json
Accept: text/event-stream
```

```json
{
  "aiAgentId": "3",
  "message": "Summarize current system's Agent, RAG, memory, and MCP capabilities",
  "sessionId": "session_demo_001",
  "userId": "user_demo",
  "maxStep": 5
}
```

---

## ⚙️ Key Configuration

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
  request-tool:
    enabled: false
    max-calls: 3

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
  flow:
    plan-review:
      enabled: false
      store-enabled: true
      ttl-seconds: 7200
  run-snapshot:
    enabled: true
    ttl-seconds: 21600
    session-index-size: 30
```

---

## 🔒 Security Notice

Do not commit to public repository:

- API Keys, Access Tokens, Cookies, Private Keys
- `.local-config`, browser state, local credentials
- Runtime logs, load test reports, temporary debug files
- Personal conversations, resumes, interview materials
- Database backups and production data

---

## 📝 Changelog

See [CHANGELOG.md](CHANGELOG.md) for update history.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).

---

## 🤝 Contributing

Focused contributions are welcome: documentation and setup improvements, reproducible smoke tests for Agent/MCP/RAG/SSE flows, small MCP governance cases, and observability improvements. Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.

**Questions or ideas?** [Open an issue](https://github.com/pengmoubuaixuexi/TAgent/issues) or [start a discussion](https://github.com/pengmoubuaixuexi/TAgent/discussions). 🙌

---

<div align="center">

Made with ❤️ by pengmoubuaixuexi

</div>
