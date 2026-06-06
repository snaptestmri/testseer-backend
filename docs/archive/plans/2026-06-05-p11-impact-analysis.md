# P11: Test Impact Analysis (IMPLEMENTED)

> **Status:** Implemented. Graph projection wired via `GraphFactProjector`; impact endpoint live at `GET /v1/impact/pr`.

**Goal:** `GET /v1/impact/pr?serviceId=&commitSha=` — given a commit SHA, return a structured report of what changed, which upstream services are affected, downstream dependencies, and which test classes to run.

**Architecture:** Three layers:
1. **P11a — GraphFactProjector** — projects `ParsedModel` → `graph_nodes`/`graph_edges` after each ingestion run
2. **ImpactAnalysisService** — 4-step pipeline: changed symbols → graph reverse reachability + outbound fallback → downstream deps → test suggestions
3. **ImpactAnalysisController** — REST endpoint with `ResponseEnvelope`, freshness, commit-specific validation, Redis cache

**Prerequisite:** P1–P10 complete. V7 migration expands `analysis_runs.job_type` to include `MANUAL` and `LOCAL`.

---

## Implemented files

```
testseer-backend/src/main/java/io/testseer/backend/
├── graph/
│   ├── GraphNodeIds.java           — deterministic node ID scheme
│   └── GraphFactProjector.java     — ingestion → graph projection
├── query/
│   └── IndexCompleteNotifier.java  — cache invalidation after index
└── analysis/
    ├── ImpactReport.java
    ├── TestClassMatcher.java       — shared with P12
    ├── CommitIndexValidator.java
    ├── ImpactAnalysisService.java
    └── ImpactAnalysisController.java

testseer-backend/src/main/resources/db/migration/
└── V7__expand_job_type_check.sql
```

---

## Endpoint

```
GET /v1/impact/pr?serviceId={id}&commitSha={sha}
```

**HTTP semantics:**
- `404` — service never indexed, or commit SHA has no COMPLETE run
- `202` — indexing in progress
- `200` — impact report returned

**Response fields:**
- `changedSymbols` — from `symbol_facts` scoped to commit
- `affectedConsumers` — graph reverse reachability + cross-service outbound call matching (`source`: `GRAPH` | `OUTBOUND_CALL`)
- `downstreamDependencies` — outbound calls from changed classes
- `suggestedTestScope` — UNIT/INTEGRATION suggestions with `exists: boolean`
- `missingTestClasses` — production classes with no matching test class

---

## Analysis pipeline

1. Query `symbol_facts WHERE service_id = ? AND commit_sha = ?`
2. For each changed CLASS/ENDPOINT: `GraphProjectionService.reverseReachability(nodeId)` + outbound call cross-match
3. Query `outbound_call_facts` for downstream deps from changed classes
4. Match test classes via `TestClassMatcher` (`*Test`, `*Tests`, `*IT`, `Test*`)

---

## Graph node ID scheme

| Node type | ID pattern |
|-----------|------------|
| SERVICE | `{serviceId}` |
| CLASS | `{serviceId}::class::{fqn}` |
| ENDPOINT | `{serviceId}::endpoint::{classFqn}#{method}` |
| SHARED_TYPE | `{serviceId}::type::{fqn}` |

---

## Tests

| Test | Type |
|------|------|
| `GraphNodeIdsTest` | unit |
| `GraphProjectionIntegrationTest.graphFactProjector_*` | integration |
| `TestClassMatcherTest` | unit |
| `ImpactAnalysisServiceTest` | unit |
| `ImpactAnalysisControllerTest` | MockMvc |
| `ImpactAnalysisIntegrationTest` | integration (Testcontainers) |

---

## Follow-ons

- **P12** — `GET /v1/gaps` (uses shared `TestClassMatcher`)
- **P14** — GitHub PR comment bot (consumer of this endpoint)
- **P15** — IntelliJ plugin inline impact (consumer of this endpoint)
