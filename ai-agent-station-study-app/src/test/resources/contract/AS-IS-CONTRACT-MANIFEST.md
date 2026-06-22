# P0-B1 AS-IS contract manifest

Generated/frozen: 2026-06-20. This file describes current behavior; it is not a target prompt specification.

## Source snapshots

| Source | SHA-256 |
|---|---|
| `docs/dev-ops/_db_prompts_dump.txt` | `7176e6114b624d8042876b55265ba17f015ca5c369c87f814a0f716e896fddaf` |
| `docs/dev-ops/_db_stepprompts_dump.txt` | `6be977066c7591c910b2de688c5ea5393a43b5e58ef58fedee9fdd5d9b42ef65` |
| `docs/dev-ops/backup_system_prompts_20260619.tsv` | `ca0df02286653bb7a19bfc1b481841cf41b99f234eb0b196057092ddccdb28f1` |

The twelve wire fixtures are deterministic structural fixtures. They intentionally do not load a live DB or call an LLM. DB text remains pinned by the hashes above; production bindings remain documented in `提示词工程实施计划.md` and `蓝图.md`.

## Current DB binding audit (read-only, 2026-06-20)

The representative fixtures use the current binding pattern verified from `ai_agent_flow_config → ai_client_config → ai_client_advisor`:

| Prototype family/client role | Explicit DB advisors before runtime propagation |
|---|---|
| Fixed `8001/DEFAULT` | LTM, Episodic, `8001_adv:RagAnswer`, ChatMemory |
| Auto `8006/TASK_ANALYZER` | LTM, Episodic, `8006_adv:RagAnswer`, ChatMemory |
| Auto `8006/PRECISION_EXECUTOR` | ChatMemory |
| Auto `8006/QUALITY_SUPERVISOR` | ChatMemory |
| Auto `8006/RESPONSE_ASSISTANT` | LTM, Episodic, ChatMemory |
| Flow `8009/TOOL_MCP` | LTM, Episodic, `8009_adv:RagAnswer`, ChatMemory |
| Flow `8009/PLANNING` | LTM, Episodic, ChatMemory |
| Flow `8009/EXECUTOR` | LTM, Episodic, ChatMemory |

Runtime `AiClientNode.collectAgentMemoryAdvisorBeanNames` propagates the memory advisor family to sibling clients; RAG remains explicit and is never propagated. This is why only Fixed-NORMAL, Auto-S1, and Flow-S1 representative fixtures include RAG.

## Main-chain wire ledger

| Stable ID | AS-IS current mechanism | Captured fixture tools | Target reference only |
|---|---|---|---|
| Fixed-NORMAL | legacy unfiltered | resident_lookup, dynamic_fetch | ALL |
| Fixed-ANSWERNOW-OFF | finalize branch does not attach tools | none | NONE |
| Fixed-ANSWERNOW-ON | finalize-tools branch | resident_lookup, dynamic_fetch | BUSINESS_ONLY |
| Fixed-STEER | inherits current unfiltered step | resident_lookup, dynamic_fetch | ALL |
| Auto-S1 | non-exec gate disabled | resident_lookup, request_tool | EVALUATION_GATE |
| Auto-S2 | legacy unfiltered execution | resident_lookup, dynamic_fetch, ask_user, request_tool | ALL |
| Auto-S3 | non-exec gate disabled | resident_lookup, request_tool | DISCOVERY_ONLY |
| Auto-S4 | normal/AnswerNow attachment branch | none in baseline variant | NONE/BUSINESS_ONLY |
| Flow-S1 | non-exec gate disabled | resident_lookup, request_tool | EVALUATION_GATE |
| Flow-S2 | non-exec gate disabled | resident_lookup, request_tool | EVALUATION_GATE |
| Flow-DAG | request System overrides default; unfiltered | resident_lookup, dynamic_fetch | ALL |
| Flow-SYNTH | currently treated as exec/unfiltered | resident_lookup, dynamic_fetch | NONE |

`target reference` never participates in P0-B1 assertions. P1 changes are expected to flip selected rows deliberately.

## Parser contracts

| Contract | AS-IS | TO-BE wave |
|---|---|---|
| AUTO-S1-COMPLETION | exact `任务状态: COMPLETED` or `完成度评估: 100%`; full-width colon misses | P0-B2 normalization |
| AUTO-S3-VERDICT | exact ASCII `是否通过: FAIL/OPTIMIZE`; everything else completes (known fail-open) | P0-B2 PASS/FAIL/OPTIMIZE/UNKNOWN |
| AUTO-S3-NULL | throws `BizException` before verdict branch | keep explicit error/UNKNOWN handling |
| FLOW-STEP-PARSE | three real parser formats; unmatched input gives empty DAG | contract hardening fixtures |
| FLOW-DEPENDS-ON | exact/legacy formats; missing line gives empty dependency set | preserve fallback |
| CRITIQUE-JSON | real `CritiqueParser`; parse failure keeps raw text | preserve fallback |

### Enacted TO-BE delta (P0-B2b-Step3, 2026-06-21)

The AS-IS column above remains the immutable historical baseline. The following delta is intentionally enforced by the
current Step3 runtime and its TO-BE tests:

| Contract | Enacted TO-BE behavior |
|---|---|
| AUTO-S3-VERDICT | only one valid `AUTO_QUALITY_VERDICT` field with no prose verdict, or with an agreeing prose verdict, may drive routing |
| AUTO-S3-CONFLICT | field/prose disagreement, prose internal conflict, missing/invalid/duplicate/unexpected machine fields, and prose-only verdicts enter one repair attempt |
| AUTO-S3-REPAIR | repair must return the canonical HTML trailer; invalid/empty repair fails closed into rework and never completes |
| AUTO-S3-TERMINAL | unrepaired UNKNOWN at the final step is delivered as `QUALITY_NOT_VERIFIED`, not as PASS |

## Runtime message order

The real four-advisor combination test freezes:

`System(default/override) → System(RAG data) → System(summary data) → history → final User(LTM + Episodic prepend)`.

The four-advisor fixture is an interaction test only. It does not claim that RAG is propagated to every client.
