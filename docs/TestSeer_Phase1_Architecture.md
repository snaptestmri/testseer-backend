# TestSeer Central Backend — Phase 1 System Architecture

> **Status:** Canonical  
> **Last verified:** 2026-06-05  
> **Supersedes:** Ad-hoc backend notes; complements OpenAPI for endpoint details

**Date:** 2026-05-21
**Phase:** Phase 1 (Weeks 3–6): Platform MVP
**Source:** TestSeer_Phase1_User_Stories.md
**Decisions recorded in:** TestSeer_Central_Backend_PRD.md (Appendix A)

**Implementation (2026-06-05):** Backend MVP and MCP are runnable locally. See [CURRENT_STATUS.md](../../docs/CURRENT_STATUS.md). Primary API-backed surface is **MCP (Cursor)**; IntelliJ plugin REST integration is **not** built. `GET /v1/gaps` and `GET /v1/graph/cross-service` are **not** exposed.

---

## 1. System Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│  Surfaces (2026-06-05)                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐               │
│  │ MCP (Cursor) │  │ IntelliJ     │  │ CLI / Maven  │               │
│  │  [shipped]   │  │ [local PSI]  │  │ [not wired]  │               │
│  └──────┬───────┘  └──────────────┘  └──────────────┘               │
│         │ REST (JSON)                                               │
└─────────┼───────────────────────────────────────────────────────────┘
          ▼
┌───────────────────────────────────────────────────────────────────┐
│  Query API  (GKE — scales independently)                          │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  /facts  /graph  /registry  /status  /impact  /admin              │ │
│  └───────────────┬──────────────────────────────────────────────┘ │
│                  │                                                  │
│         ┌────────┴──────────┐                                      │
│         ▼                   ▼                                      │
│     Redis Cache        Postgres (Cloud SQL)                        │
│     (hot facts)        graph_nodes / graph_edges                   │
│                        fact tables / registry                      │
└───────────────────────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────────────────────┐
│  Ingestion Pipeline  (GKE)                                        │
│                                                                   │
│  GitHub ──webhook──▶ Webhook Receiver ──▶ Kafka                  │
│                       (sig validation)    ┌──────────────────┐   │
│                                           │ testseer.jobs.pr  │   │
│  Scheduler ──nightly (planned)────────▶   │ testseer.jobs.batch│  │
│                                           └────────┬─────────┘   │
│                                                    │              │
│                                           Analysis Workers (GKE)  │
│                                           ┌────────────────────┐  │
│                                           │ JavaParser + Rules  │  │
│                                           │ Peripheral Detector │  │
│                                           │ Fact Normalizer     │  │
│                                           └────────┬───────────┘  │
│                                                    │              │
│                              ┌─────────────────────┴──────────┐  │
│                              ▼                                 ▼  │
│                         Postgres                           MongoDB │
│                    (structured facts,               (raw parsed    │
│                     graph edges)                    models, ASTs)  │
│                                                                   │
│                         (Pub/Sub → plugin: planned, not built)      │
└───────────────────────────────────────────────────────────────────┘
```

---

## 2. Component Responsibilities

### 2.1 Webhook Receiver

**Responsibility:** Accept GitHub webhook events, validate signatures, publish index jobs to Kafka.

**Triggered by:**
- `pull_request` events (opened, synchronize, reopened)
- `push` events on the default branch

**Behaviour:**
- Validate `X-Hub-Signature-256` header using the registered GitHub App secret (from GCP Secret Manager) before any processing
- Look up the repo in the Service Registry; silently drop + log if not registered
- Decompose the push/PR into per-service jobs based on changed file paths and registry source root mappings
- Publish each job as a `IngestionJob` envelope to the appropriate Kafka topic:
  - PR events → `testseer.jobs.pr` (high-priority topic)
  - Default branch push / nightly → `testseer.jobs.batch` (low-priority topic)
- Respond `202 Accepted` immediately; all processing is async

**Does not:**
- Parse Java source
- Write to Postgres or MongoDB
- Communicate with the Query API

**Failure handling:**
- Invalid signature → `401`, log and drop
- Unregistered repo → `200`, log and drop (do not error — prevents webhook retry storms)
- Kafka publish failure → `500`, GitHub retries automatically

---

### 2.2 Service Registry

**Responsibility:** Authoritative record of which repos and services TestSeer should index.

**Storage:** Postgres `service_registry` table (see §4.1).

**REST API** (served by Query API process, not a separate service):

| Method | Path | Description |
|---|---|---|
| `POST` | `/registry/services` | Register a new service |
| `GET` | `/registry/services` | List all registered services |
| `GET` | `/registry/services/{serviceId}` | Get a single service entry |
| `PATCH` | `/registry/services/{serviceId}` | Update overrides or disable |
| `DELETE` | `/registry/services/{serviceId}` | Soft-delete (sets `enabled = false`) |

**Validation on registration:**
- Required: `org_id`, `repo`, `service_name`, `build_tool`
- `module_type` defaults to `service`; accepts `library`
- Missing required fields → `400` with field-level error messages
- Duplicate `(org_id, repo, service_name)` → `409 Conflict`

**Disable behaviour:**
- Sets `enabled = false`; does not delete row or cascade-delete facts
- Webhook Receiver checks `enabled` before publishing jobs — disabled services are silently skipped

---

### 2.3 Kafka Topics

| Topic | Priority | Consumer Group | Retention |
|---|---|---|---|
| `testseer.jobs.pr` | High | `testseer-workers-pr` | 24 hours |
| `testseer.jobs.batch` | Low | `testseer-workers-batch` | 72 hours |
| `testseer.jobs.dlq` | — | monitored, no auto-consumer | 7 days |

**Job envelope schema** (`IngestionJob`):

```json
{
  "jobId": "uuid",
  "jobType": "PR | PUSH | NIGHTLY",
  "orgId": "example-org",
  "repo": "order-service",
  "serviceId": "svc-orders-001",
  "commitSha": "abc123",
  "changedFiles": ["src/main/java/com/example/OrderController.java"],
  "prNumber": 42,
  "enqueuedAt": "2026-05-21T10:00:00Z",
  "attempt": 1
}
```

**Priority enforcement:**
- Two separate consumer groups with independent GKE deployments
- PR consumer group has higher pod replica count and resource allocation
- Batch consumer group scales down during business hours via GKE HPA based on queue depth

**Retry policy:**
- Attempt 1 → immediate
- Attempt 2 → 30s delay
- Attempt 3 → 5min delay
- Attempt 4+ → dead-letter topic (`testseer.jobs.dlq`); alert fires

---

### 2.4 Analysis Workers

**Responsibility:** Consume `IngestionJob` from Kafka, parse Java source, extract facts, write to Postgres and MongoDB.

**Processing pipeline per job:**

```
Consume job
    │
    ▼
Fetch source files from GitHub API (changed files only for incremental; all for baseline)
    │
    ▼
Parse with JavaParser + SymbolSolver
    │
    ▼
Extract facts:
  ├── Endpoint mappings (@GetMapping, @PostMapping, etc.)
  ├── Constructor + field dependencies
  ├── Outbound calls (RestClient, WebClient, RestTemplate, Feign)
  ├── Class/method annotations
  └── Peripheral detection (Tier 1 / 2 / 3 classification)
    │
    ▼
Normalise to FactBatch (schema-versioned)
    │
    ▼
Atomic dual-write:
  ├── Postgres: fact tables + graph edges (see §4)
  └── MongoDB: raw ParsedModel document
    │
    ▼
Commit Kafka offset (ONLY after successful dual-write)
    │
    ▼
Publish "index complete" event to GCP Pub/Sub
```

**Peripheral detection tiers (from PRD):**

| Tier | Signals | Emitted Prerequisite |
|---|---|---|
| 1 — High confidence | `@KafkaListener`, `RedisTemplate`, `@Document`, JPA + Postgres dialect, `AmazonS3`, `@RabbitListener` | Direct Testcontainers / LocalStack recommendation |
| 2 — Possible on-prem | Oracle JDBC driver, SQL Server driver, ambiguous JDBC URL (env var reference) | "Verify before using Testcontainers" + detected signal |
| 3 — Unknown | Spring Cloud Config / Vault datasource, proprietary client match via rule pack, dynamic datasource routing | "Manual setup required — declare in `.testseer/config.yml`" + reason code |

**Unsupported construct handling:**
- If JavaParser throws on a file → emit `UnsupportedConstructFact` with `reason_code` and `file_path`; continue processing remaining files in the job
- Never silently omit a file from the index

**Kafka offset commit policy:**
- Auto-commit disabled
- Offset committed only after both Postgres and MongoDB writes succeed
- On write failure → do not commit; Kafka redelivers the job on next poll
- On repeated failure → `attempt` counter incremented; after max attempts → dead-letter

---

### 2.5 Fact Store (Postgres)

See §4 for full schema. Key design decisions:

- All tables partitioned on `org_id` from day one (multi-tenant expansion proofing)
- Baseline snapshot vs. incremental delta tracked via `snapshot_type` column: `BASELINE | DELTA`
- `indexed_at` and `commit_sha` on every row — freshness metadata for every fact
- Schema version in `fact_schema_version` column; breaking changes require CHANGELOG entry and snapshot update
- Indexes on `(from_node, edge_type)` and `(to_node, edge_type)` for graph traversal (confirmed fast in Phase 0 spike)

**MongoDB** stores `ParsedModel` documents (raw AST fragments, JavaParser output) keyed by `{orgId}/{repo}/{serviceId}/{commitSha}`. Not queried by the Query API in Phase 1 — reserved for re-processing and debugging.

---

### 2.6 Graph Projection Layer

**Storage:** Postgres `graph_nodes` and `graph_edges` tables (same database as fact store).

**Materialized edge types:**

| Edge type | Meaning |
|---|---|
| `CALLS` | Service → Service (transitive call dependency) |
| `DEPENDS_ON` | Class → Class (constructor / field dependency) |
| `OUTBOUND_TO` | Endpoint → Endpoint (cross-service HTTP call) |
| `USES_TYPE` | Service → SharedType (shared library type usage) |

**Traversal queries** (all implemented as Postgres recursive CTEs; see Phase 0 spike):

| Query | CTE pattern | p95 at 40-service scale |
|---|---|---|
| Forward reachability (service) | `UNION` recursive on `CALLS` | 3ms |
| Forward reachability (class) | `UNION` recursive on `DEPENDS_ON` | 3ms |
| Outbound traversal | `UNION` recursive on `OUTBOUND_TO` | 2ms |
| Reverse reachability (impact) | `UNION` recursive on `CALLS\|DEPENDS_ON\|USES_TYPE` reverse | 2ms |
| Immediate neighborhood | Single join on `from_node` | 1ms |
| Cross-service boundary | `WITH RECURSIVE start_svc` + final service filter | 3ms |
| Shared type resolution | Point lookup on `symbol_fqn` where `module_type = 'library'` | 1ms |
| Type usage fan-out | Reverse join on `USES_TYPE` | 1ms |

**Incremental edge update** (on changed file):
1. Delete existing edges from affected `symbol_fqn` nodes
2. Insert new edges from re-parsed facts
3. Both steps in a single Postgres transaction; completes < 5ms (spike confirmed)

**Circular topology handling:**
All cross-service boundary CTEs use `WITH RECURSIVE start_svc AS (SELECT service FROM graph_nodes WHERE id = ?)` and filter final results with `WHERE n.service <> s.service` to prevent circular topology (e.g. notifications → orders) from returning start-service nodes as results. (Identified and fixed in Phase 0 spike.)

---

### 2.7 Redis Cache

**Responsibility:** Cache Query API responses for frequently queried repos and services.

**Cache key structure:** `testseer:{orgId}:{repo}:{serviceId}:{queryType}:{params_hash}`

**Invalidation trigger:** Kafka consumer in Query API process listens on an internal `testseer.index.complete` topic; on receipt, invalidates all cache keys for the affected `(orgId, repo, serviceId)`.

**TTL:** 1 hour (safety net only — primary invalidation is event-driven).

**Cache-aside pattern:**
1. Query API checks Redis
2. Cache hit → return immediately (target < 50ms)
3. Cache miss → query Postgres, write to Redis, return

**Not cached:**
- Registry CRUD endpoints
- Freshness metadata (`indexed_at`, `commit_sha`) — always read from Postgres to ensure accuracy

---

### 2.8 Query API

**Responsibility:** Serve facts, graph traversal, registry, impact analysis, and admin triggers to MCP clients and direct REST callers.

**Deployment:** Separate GKE deployment from Analysis Workers. Scales on CPU / request rate independently.

**Endpoints (Phase 1 — see [OpenAPI](openapi.yaml) for authoritative list):**

| Method | Path | Description | Cache | Shipped |
|---|---|---|---|---|
| `GET` | `/v1/facts/class` | Facts for a class by `symbol_fqn` | Yes | Yes |
| `GET` | `/v1/facts/outbound` | Outbound calls for a service | Yes | Yes |
| `GET` | `/v1/facts/by-file` | Symbols for file paths (MCP PR flow) | Yes | Yes |
| `GET` | `/v1/graph/reachability` | Transitive service/class reachability | Yes | Yes |
| `GET` | `/v1/graph/impact` | Reverse reachability (impact set) | Yes | Yes |
| `GET` | `/v1/graph/neighborhood` | Depth-1 neighbors of a node | Yes | Yes |
| `GET` | `/v1/graph/shared-type` | Shared type resolution by `symbol_fqn` | Yes | Yes |
| `GET` | `/v1/graph/type-fanout` | Type usage fan-out | Yes | Yes |
| — | `/v1/graph/cross-service` | Cross-service boundary traversal | — | **No REST** (service method only) |
| `GET` | `/v1/impact/pr` | PR impact analysis | No | Yes |
| `GET` | `/v1/services/{id}/description` | LLM service description | Yes | Yes |
| `POST` | `/v1/services/{id}/description` | Regenerate description | — | Yes |
| `GET` | `/v1/status/{serviceId}` | Index status and freshness metadata | No | Yes |
| `POST` | `/registry/services` | Register a service | — | Yes |
| `GET` | `/registry/services` | List registered services | — | Yes |
| `GET` | `/registry/services/{serviceId}` | Get one service | — | Yes |
| `PATCH` | `/registry/services/{serviceId}` | Update / disable a service | — | Yes |
| `DELETE` | `/registry/services/{serviceId}` | Soft-delete (disable) | — | Yes |
| `POST` | `/admin/index/{serviceId}` | On-demand re-index | — | Yes |
| `POST` | `/admin/index/local` | Index local filesystem | — | Yes |
| `POST` | `/admin/discover` | GitHub org discovery | — | Yes |
| — | `/v1/gaps` | Portfolio test gap report | — | **Planned (P12)** |

**Standard response envelope:**

```json
{
  "schemaVersion": "1.0",
  "indexedAt": "2026-05-21T10:05:00Z",
  "commitSha": "abc123",
  "freshnessStatus": "CURRENT | STALE | INDEXING | NOT_INDEXED",
  "data": { ... }
}
```

**Freshness status logic:**

| Status | Condition |
|---|---|
| `CURRENT` | `indexed_at` within stale threshold (default 1 hour) |
| `STALE` | `indexed_at` beyond stale threshold |
| `INDEXING` | Job in-flight in Kafka for this service |
| `NOT_INDEXED` | No facts found for this serviceId |

**GCP Pub/Sub integration:**
- Query API publishes `IndexReadyEvent` to Pub/Sub on successful index completion
- IntelliJ plugin subscribes and surfaces "fresh index available" banner — no polling required

**Error responses:**

| Scenario | HTTP | Body |
|---|---|---|
| ServiceId not registered | 404 | `{"error": "NOT_REGISTERED", "message": "...", "hint": "Register via POST /registry/services"}` |
| Index in progress | 202 | `{"freshnessStatus": "INDEXING", "lastKnownFacts": {...}}` |
| Upstream Postgres unavailable | 503 | `{"error": "STORE_UNAVAILABLE"}` |

---

## 3. Data Flows

### 3.1 PR Push → Index → Plugin Notification

```
Developer pushes to PR branch
        │
        ▼
GitHub fires pull_request webhook
        │
        ▼
Webhook Receiver
  ├── Validate X-Hub-Signature-256
  ├── Look up repo in service_registry
  ├── Decompose changed files → per-service IngestionJobs
  └── Publish to testseer.jobs.pr (Kafka)
        │
        ▼
Analysis Worker (PR consumer group)
  ├── Fetch changed .java files from GitHub API
  ├── Parse with JavaParser + SymbolSolver
  ├── Extract facts + peripheral detection
  ├── Dual-write: Postgres facts + graph edges / MongoDB raw model
  ├── Commit Kafka offset
  └── Publish IndexCompleteEvent to Pub/Sub
        │
        ▼
Query API (Pub/Sub consumer)
  └── Invalidate Redis cache for (orgId, repo, serviceId)
        │
        ▼
IntelliJ Plugin (Pub/Sub subscriber)
  └── Show "Fresh index available" banner to QA engineer

Total target time: < 5 minutes from push to plugin notification
```

### 3.2 QA Engineer Requests a Test Plan

```
QA engineer right-clicks class in IntelliJ → "Generate Test Plan"
        │
        ▼
Plugin calls GET /v1/facts/class?symbolFqn=...&serviceId=...
        │
        ▼
Query API
  ├── Check Redis cache
  │     ├── HIT  → return cached facts (< 50ms)
  │     └── MISS → query Postgres
  │                   ├── Retrieve fact rows (baseline + deltas)
  │                   ├── Run graph traversal CTEs for dependencies
  │                   ├── Write result to Redis
  │                   └── Return response (< 300ms p95)
        │
        ▼
Plugin receives response with freshnessStatus
  ├── CURRENT     → generate plan from facts
  ├── STALE       → show warning banner, generate plan from facts
  ├── INDEXING    → show "indexing in progress", offer last known facts
  └── NOT_INDEXED → show "not yet indexed" message with registration instructions
```

### 3.3 Nightly Baseline Re-index

```
Cloud Scheduler fires nightly cron (off-peak)
        │
        ▼
Scheduler service publishes full-index IngestionJobs
to testseer.jobs.batch for every enabled service in registry
        │
        ▼
Analysis Workers (batch consumer group)
  ├── Fetch all .java files (not just changed files)
  ├── Parse + extract (same pipeline as incremental)
  ├── Write facts with snapshot_type = BASELINE
  ├── Replace graph edges for service (truncate + reinsert in transaction)
  └── Commit offset
```

---

## 4. Database Schema

### 4.1 Postgres

```sql
-- Service Registry
CREATE TABLE service_registry (
    service_id      VARCHAR(255) PRIMARY KEY,
    org_id          VARCHAR(100) NOT NULL,
    repo            VARCHAR(255) NOT NULL,
    service_name    VARCHAR(255) NOT NULL,
    module_type     VARCHAR(50)  NOT NULL DEFAULT 'service',  -- 'service' | 'library'
    build_tool      VARCHAR(50)  NOT NULL,
    source_roots    TEXT[]       NOT NULL DEFAULT '{"src/main/java"}',
    test_roots      TEXT[]       NOT NULL DEFAULT '{"src/test/java"}',
    owner_team      VARCHAR(255),
    enabled         BOOLEAN      NOT NULL DEFAULT true,
    metadata        JSONB,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (org_id, repo, service_name)
);

-- Symbol Facts
CREATE TABLE symbol_facts (
    id              BIGSERIAL    PRIMARY KEY,
    org_id          VARCHAR(100) NOT NULL,
    repo            VARCHAR(255) NOT NULL,
    service_id      VARCHAR(255) NOT NULL REFERENCES service_registry(service_id),
    commit_sha      VARCHAR(40)  NOT NULL,
    file_path       VARCHAR(500) NOT NULL,
    symbol_fqn      VARCHAR(500) NOT NULL,
    symbol_kind     VARCHAR(50)  NOT NULL,  -- 'CLASS' | 'METHOD' | 'ENDPOINT'
    snapshot_type   VARCHAR(10)  NOT NULL,  -- 'BASELINE' | 'DELTA'
    attributes      JSONB,
    evidence_source VARCHAR(50)  NOT NULL,
    confidence      FLOAT        NOT NULL DEFAULT 1.0,
    fact_schema_version VARCHAR(10) NOT NULL DEFAULT '1.0',
    indexed_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Outbound Call Facts
CREATE TABLE outbound_call_facts (
    id              BIGSERIAL    PRIMARY KEY,
    org_id          VARCHAR(100) NOT NULL,
    repo            VARCHAR(255) NOT NULL,
    service_id      VARCHAR(255) NOT NULL REFERENCES service_registry(service_id),
    commit_sha      VARCHAR(40)  NOT NULL,
    source_symbol   VARCHAR(500) NOT NULL,
    http_method     VARCHAR(10),
    path            VARCHAR(500),
    snapshot_type   VARCHAR(10)  NOT NULL,
    evidence_source VARCHAR(50)  NOT NULL,
    confidence      FLOAT        NOT NULL DEFAULT 1.0,
    indexed_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Peripheral Facts
CREATE TABLE peripheral_facts (
    id              BIGSERIAL    PRIMARY KEY,
    org_id          VARCHAR(100) NOT NULL,
    service_id      VARCHAR(255) NOT NULL REFERENCES service_registry(service_id),
    commit_sha      VARCHAR(40)  NOT NULL,
    peripheral_type VARCHAR(100) NOT NULL,  -- 'kafka' | 'redis' | 'oracle' | 'mongodb' | ...
    detection_tier  SMALLINT     NOT NULL,  -- 1 | 2 | 3
    detection_signals JSONB      NOT NULL,
    prerequisite_text TEXT       NOT NULL,
    reason_code     VARCHAR(100),           -- for tier 3
    indexed_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Unsupported Construct Facts
CREATE TABLE unsupported_construct_facts (
    id              BIGSERIAL    PRIMARY KEY,
    org_id          VARCHAR(100) NOT NULL,
    service_id      VARCHAR(255) NOT NULL REFERENCES service_registry(service_id),
    commit_sha      VARCHAR(40)  NOT NULL,
    file_path       VARCHAR(500) NOT NULL,
    reason_code     VARCHAR(100) NOT NULL,
    detail          TEXT,
    indexed_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Analysis Runs (job tracking)
CREATE TABLE analysis_runs (
    job_id          VARCHAR(255) PRIMARY KEY,
    org_id          VARCHAR(100) NOT NULL,
    service_id      VARCHAR(255) NOT NULL REFERENCES service_registry(service_id),
    commit_sha      VARCHAR(40)  NOT NULL,
    job_type        VARCHAR(20)  NOT NULL,  -- 'PR' | 'PUSH' | 'NIGHTLY'
    status          VARCHAR(20)  NOT NULL,  -- 'QUEUED' | 'RUNNING' | 'COMPLETE' | 'FAILED' | 'DLQ'
    attempt         SMALLINT     NOT NULL DEFAULT 1,
    enqueued_at     TIMESTAMPTZ  NOT NULL,
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    error_detail    TEXT
);

-- Graph Nodes
CREATE TABLE graph_nodes (
    id              VARCHAR(255) PRIMARY KEY,
    org_id          VARCHAR(100) NOT NULL,
    repo            VARCHAR(255) NOT NULL,
    service         VARCHAR(255) NOT NULL,
    module_type     VARCHAR(50)  NOT NULL DEFAULT 'service',
    node_type       VARCHAR(50)  NOT NULL,  -- 'SERVICE' | 'CLASS' | 'ENDPOINT' | 'SHARED_TYPE'
    symbol_fqn      VARCHAR(500)
);

-- Graph Edges
CREATE TABLE graph_edges (
    id              BIGSERIAL    PRIMARY KEY,
    from_node       VARCHAR(255) NOT NULL REFERENCES graph_nodes(id),
    to_node         VARCHAR(255) NOT NULL REFERENCES graph_nodes(id),
    edge_type       VARCHAR(50)  NOT NULL,  -- 'CALLS' | 'DEPENDS_ON' | 'OUTBOUND_TO' | 'USES_TYPE'
    confidence      FLOAT        NOT NULL DEFAULT 1.0,
    evidence_source VARCHAR(50)
);

-- Indexes
CREATE INDEX idx_symbol_facts_service   ON symbol_facts(org_id, service_id, commit_sha);
CREATE INDEX idx_symbol_facts_fqn       ON symbol_facts(symbol_fqn);
CREATE INDEX idx_outbound_service       ON outbound_call_facts(org_id, service_id, commit_sha);
CREATE INDEX idx_peripheral_service     ON peripheral_facts(org_id, service_id);
CREATE INDEX idx_analysis_runs_service  ON analysis_runs(service_id, status);
CREATE INDEX idx_edges_from             ON graph_edges(from_node, edge_type);
CREATE INDEX idx_edges_to               ON graph_edges(to_node, edge_type);
CREATE INDEX idx_nodes_fqn              ON graph_nodes(symbol_fqn);
CREATE INDEX idx_nodes_service          ON graph_nodes(org_id, service);
```

### 4.2 MongoDB Collection: `parsed_models`

```json
{
  "_id": "{orgId}/{repo}/{serviceId}/{commitSha}",
  "orgId": "example-org",
  "repo": "order-service",
  "serviceId": "svc-orders-001",
  "commitSha": "abc123",
  "indexedAt": "2026-05-21T10:05:00Z",
  "changedFiles": ["src/main/java/..."],
  "models": [
    {
      "filePath": "src/main/java/com/example/OrderController.java",
      "classFqn": "com.example.OrderController",
      "rawAst": { ... },
      "annotations": ["@RestController", "@RequestMapping(\"/orders\")"],
      "constructorParams": [...],
      "outboundCalls": [...]
    }
  ]
}
```

---

## 5. GKE Deployment Topology

```
GKE Cluster (testseer-prod)
├── Namespace: testseer-ingestion
│   ├── Deployment: webhook-receiver
│   │   └── 2 replicas (stateless, scales on request rate)
│   ├── Deployment: analysis-worker-pr
│   │   └── 4–16 replicas (HPA on Kafka consumer lag, testseer.jobs.pr)
│   └── Deployment: analysis-worker-batch
│       └── 1–4 replicas (HPA on Kafka consumer lag, testseer.jobs.batch)
│
├── Namespace: testseer-query
│   └── Deployment: query-api
│       └── 2–8 replicas (HPA on CPU/request rate; independent of ingestion)
│
└── Namespace: testseer-infra (managed externally, referenced here)
    ├── Cloud SQL (Postgres 16) — private VPC endpoint only
    ├── Redis (Memorystore) — private VPC endpoint only
    ├── Confluent Cloud Kafka — accessed via private link
    ├── MongoDB Atlas — accessed via private endpoint
    └── GCP Pub/Sub — standard GCP service
```

**Network policy:** No pod-to-pod communication between `testseer-ingestion` and `testseer-query` namespaces. Both access Postgres and Redis via VPC-internal endpoints. No public database endpoints.

**Secrets:** All credentials (GitHub App private key, Postgres password, MongoDB URI, Kafka API key) stored in GCP Secret Manager and mounted as environment variables via Workload Identity — no secrets in container images or Kubernetes Secrets YAML.

---

## 6. Architecture Decision Records

### ADR-001: Graph Storage — Postgres Recursive CTEs
**Status:** Accepted (Phase 0 spike, 2026-05-22)
**Decision:** Use Postgres recursive CTEs for graph projection. Neo4j rejected.
**Rationale:** At 40-service scale (500 nodes, 1,480 edges), all 9 benchmark queries completed under 3ms p95 in Postgres vs 7–23ms in Neo4j. No performance justification for Neo4j's operational overhead.
**Consequence:** Re-evaluate if portfolio exceeds 200 services or traversal p95 exceeds 50ms in production.

---

### ADR-002: Dual-Write Postgres + MongoDB
**Status:** Accepted
**Decision:** Analysis workers write structured facts to Postgres and raw `ParsedModel` to MongoDB atomically. Kafka offset committed only after both succeed.
**Rationale:** Postgres serves the query path (fast, structured, indexed). MongoDB preserves the full parsed model for debugging and future re-processing without re-fetching from GitHub.
**Consequence:** MongoDB write failure rolls back the Postgres write and retries the job via Kafka. Adds latency on the write path (acceptable; write SLO is seconds, not milliseconds).

---

### ADR-003: Per-Service Job Decomposition
**Status:** Accepted
**Decision:** Webhook Receiver decomposes incoming events into per-service `IngestionJob` records, not per-repo jobs.
**Rationale:** A monorepo with 40 services should not re-index all 40 when 3 files change in one service. Per-service decomposition using registry source root mappings ensures only affected services are re-indexed.
**Consequence:** Webhook Receiver must join changed file paths against registry `source_roots` to determine which services are affected. Changes outside any registered source root are silently ignored.

---

### ADR-004: Kafka Offset Committed After Write, Not After Parse
**Status:** Accepted
**Decision:** Analysis workers commit Kafka offsets only after both Postgres and MongoDB writes succeed, not after parsing.
**Rationale:** Prevents job loss on pod restart or write failure. If a worker crashes after parsing but before writing, Kafka redelivers the job and the worker re-parses and re-writes. Parsing is idempotent; writes use `ON CONFLICT DO UPDATE` (Postgres) and `replaceOne` (MongoDB).
**Consequence:** Duplicate parsing is possible but harmless. Write idempotency must be maintained.

---

### ADR-005: Query API Serves Registry CRUD
**Status:** Accepted
**Decision:** Registry CRUD endpoints are served by the Query API process, not a separate Registry service.
**Rationale:** Phase 1 scope. Registry operations are infrequent (engineering lead configuration, not hot path). A separate service adds operational overhead without benefit at this scale.
**Consequence:** If registry write load grows significantly (e.g. automated repo onboarding), extract to a dedicated service in a future phase.

---

## 7. Open Questions Remaining for Phase 1

| Question | Owner | Impact |
|---|---|---|
| Stale index threshold default (1 hour?) | QA Engineering leads | Query API `freshnessStatus` logic |
| GitHub App vs webhook secret for auth | Engineering | Webhook Receiver signature validation implementation |
| Nightly scheduler: Cloud Scheduler vs Kafka-native scheduling | Engineering | Batch job trigger implementation |
| MongoDB Atlas tier / region co-location with GKE | Platform | Write latency on dual-write path |
