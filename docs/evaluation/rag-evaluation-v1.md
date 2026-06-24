# RAG Evaluation V1

## Status

- Dataset: frozen at 60 cases in `ai-agent-station-app/src/test/resources/evaluation/rag-evaluation-v1.jsonl`.
- SHA-256: `8D20DF2593CEBCC3A1C23827D2FEFD353CF12ADA8CE1698325BD6DB6C855CA97`.
- Default unit-test validation: enabled and network-free.
- Live baseline and adaptive comparison: not yet recorded. Do not claim numerical improvements before a live run produces the report.
- 2026-06-23 live preflight reached Harness decisions, project retrieval and MCP ToolCallback injection. The three-mode run was not started because the configured DashScope account returned `free quota exhausted`.

## Comparison Modes

1. `PGVECTOR_ONLY`
2. `FIXED_ADVANCED_RAG_BASELINE`
3. `ADAPTIVE_AGENTIC_RETRIEVAL`

Comparison implementations belong to test/evaluation code only. Production has one retrieval entry: `EvidenceRetrievalService`.

## Dataset Distribution

| Category | Cases |
|---|---:|
| Project semantic QA | 15 |
| Exact technical terms | 10 |
| Official documentation | 10 |
| Cross-source questions | 10 |
| No-evidence or conflict | 10 |
| Session follow-up | 5 |

Memory acceptance is additionally covered by 12+ deterministic multi-turn unit scenarios; it is not mislabeled as 30 independent QA cases.

## Metrics

- Source routing accuracy
- Hit@5 and MRR
- Refusal F1
- Citation validity
- Answer key-point coverage
- Faithfulness from a separately configured judge
- Model-call count and P95 latency

## Retention Gates

- Keep BM25/RRF only if the exact-term subset improves Hit@5 by at least 10 percentage points or independently repairs at least 3 cases.
- Keep Small-to-Big only if long-context key-point coverage improves by at least 5 percentage points or repairs at least 3 cases, while Faithfulness drops by no more than 2 percentage points.
- Keep second retrieval only if it recovers at least 20% and at least 3 first-round failures.

Features that do not meet these gates must be removed from production code, configuration, Docker, tests, README, and resume material together.
