# TestSeer Central Backend — Phase 1 System Design

> **Status:** Canonical  
> **Last verified:** 2026-06-12  
> **Authoritative for:** Trade-offs, SLOs, revisit triggers (§6)

**Implementation (2026-06-12):** See [CURRENT_STATUS.md](../../docs/CURRENT_STATUS.md). OpenAPI and [TestSeer_REST_API_Design.md](TestSeer_REST_API_Design.md) supersede endpoint/error tables when they differ.

**Phase:** Phase 1 (Weeks 3–6): Platform MVP
**Date:** 2026-05-21
**Sources:** TestSeer_Phase1_User_Stories.md, TestSeer_Phase1_Architecture.md
**Complements:** TestSeer_Phase1_Architecture.md (authoritative for schema + ADRs)

---

## 1. Requirements

### 1.1 Functional Requirements

| ID | Requirement | Source |
|----|-------------|--------|
| F-01 | Register, update, and disable services with validation (org, repo, service name, build tool required) | Registry user stories |
| F-02 | Automatically trigger an index job when a PR is opened or updated on a registered repo | Webhook Ingestion |
| F-03 | Validate GitHub webhook signatures before processing any payload | Webhook Ingestion |
| F-04 | Trigger incremental index on default-branch push; reflect changes within 5 minutes | Webhook Ingestion |
| F-05 | Execute PR index jobs before batch jobs when both are queued | Kafka priority separation |
| F-06 | Retry failed jobs with backoff; move to dead-letter after exhausting retries | Kafka retry policy |
| F-07 | Extract endpoint mappings, constructor dependencies, and outbound calls from Java source | Analysis Workers |
| F-08 | Classify peripherals into Tier 1/2/3 and emit appropriate prerequisite recommendations | Peripheral detection |
| F-09 | Emit an explicit `UnsupportedConstructFact` for any unparseable Java construct | Analysis Workers |
| F-10 | Write structured facts to Postgres and raw parsed models to MongoDB atomically | Dual-write |
| F-11 | Process changed files per service (not per repo) to avoid unnecessary re-indexing | Per-service decomposition |
| F-12 | Maintain both baseline snapshot and incremental deltas per commit | Fact store |
| F-13 | Resolve transitive service call chains with no depth limit | Graph traversal |
| F-14 | Determine which services are affected when a shared DTO changes (reverse reachability) | Graph traversal |
| F-15 | Follow outbound call edges across service boundaries and resolve remote endpoint definitions | Cross-service traversal |
| F-16 | Resolve shared library types to canonical `symbol_fqn` with provenance metadata | Shared type resolution |
| F-17 | Return `freshnessStatus` (CURRENT / STALE / INDEXING / NOT_INDEXED) with every response | Query API |
| F-18 | Fall back transparently when Query API is unreachable (plugin-side behaviour) | Query API |
| F-19 | Schema-version all responses so future optional fields never break existing consumers | Query API |
| F-20 | Drop events for unregistered repos silently (log only; no failed job) | Webhook Ingestion |

### 1.2 Non-Functional Requirements

| Category | Requirement | Target |
|----------|-------------|--------|
| **Latency — read** | Query API P95 end-to-end | < 300ms |
| **Latency — cache hit** | Redis cache hit | < 50ms |
| **Latency — graph traversal** | Full unbounded reachability at 40-service scale | < 100ms (spike: ≤ 3ms p95) |
| **Latency — incremental write** | Edge update for a single changed file | < 5ms (spike: ≤ 2ms p95) |
| **Freshness** | PR changes reflected in Query API after push | < 5 minutes |
| **Availability** | Query API uptime | ≥ 99.5% |
| **Throughput** | Initial target (single-tenant, 100-repo org) | ~200 index jobs/day; burst ~50 concurrent PR jobs |
| **Scalability** | Ingestion and query scale independently | HPA per deployment |
| **Durability** | No fact loss on worker crash | Kafka at-least-once + idempotent writes |
| **Correctness** | Partial write failure leaves no inconsistent state | Kafka offset after dual-write |
| **Security** | No credentials in images or K8s Secrets YAML | GCP Secret Manager + Workload Identity |
| **Observability** | Alerting on DLQ depth, job latency, cache miss rate | Cloud Monitoring |

### 1.3 Constraints

| Constraint | Detail |
|------------|--------|
| **Infrastructure** | GCP only: GKE, Cloud SQL (Postgres 16), Memorystore (Redis), Pub/Sub, Secret Manager |
| **Message broker** | Confluent Cloud Kafka (managed, private link) |
| **Document store** | MongoDB Atlas (private endpoint, same region as GKE) |
| **Language (workers)** | Java (JavaParser + SymbolSolver for parsing) |
| **Org model** | Single-tenant Phase 1; `org_id` partition key enables future multi-tenant expansion |
| **Timeline** | Weeks 3–6; no breaking schema changes without CHANGELOG entry + snapshot update |
| **Team size** | Small team; no operational overhead for separate graph DB justified |
| **Phase 0 constraint** | Graph storage decision locked: Postgres recursive CTEs (Neo4j rejected) |

---

## 2. High-Level Design

### 2.1 Component Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Client Surfaces                                                         │
│  ┌───────────────────┐    ┌──────────────────┐                          │
│  │  IntelliJ Plugin  │    │  CLI / Maven      │                          │
│  └─────────┬─────────┘    └────────┬─────────┘                          │
│            │  REST JSON             │  REST JSON                          │
└────────────┼───────────────────────┼────────────────────────────────────┘
             ▼                       ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  Query API  ─── testseer-query namespace (GKE, 2–8 replicas)            │
│                                                                          │
│  /v1/facts   /v1/graph   /v1/status   /registry                         │
│       │             │                                                    │
│  Redis Cache   Postgres (Cloud SQL)        GCP Pub/Sub                  │
│  (hot reads)   (facts + graph + registry)  (push notifications out)     │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│  Ingestion Pipeline ─── testseer-ingestion namespace (GKE)              │
│                                                                          │
│  GitHub ─── webhook ──▶ Webhook Receiver (2 replicas)                  │
│                          sig validation                                  │
│                          registry lookup                                 │
│                          per-service decomposition                       │
│                                │                                         │
│                    ┌───────────┴─────────────┐                          │
│                    ▼                         ▼                           │
│          testseer.jobs.pr           testseer.jobs.batch                  │
│          (Kafka, high prio)         (Kafka, low prio)                    │
│                    │                         │                           │
│          Analysis Workers PR        Analysis Workers Batch               │
│          (4–16 replicas HPA)        (1–4 replicas HPA)                  │
│                    │                                                      │
│            JavaParser + SymbolSolver                                     │
│            Peripheral Detector                                           │
│            Fact Normalizer                                               │
│                    │                                                      │
│          ┌─────────┴────────┐                                            │
│          ▼                  ▼                                            │
│      Postgres           MongoDB Atlas                                    │
│  (facts + graph)        (raw ParsedModel)                                │
│          │                                                               │
│          └──▶ GCP Pub/Sub ──▶ Query API (cache invalidation)            │
│                          └──▶ IntelliJ Plugin (index ready banner)      │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│  Scheduled Batch                                                         │
│  Cloud Scheduler ─── nightly cron ──▶ testseer.jobs.batch               │
└─────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Data Flow Summary

**Happy-path PR index (target: < 5 min push-to-notification):**
```
GitHub push ──▶ Webhook Receiver (sig validation + decomposition, <1s)
           ──▶ Kafka testseer.jobs.pr (enqueue)
           ──▶ Analysis Worker (fetch + parse + dual-write, ~60–120s)
           ──▶ GCP Pub/Sub IndexCompleteEvent
           ──▶ Query API invalidates Redis + Plugin shows banner
```

**QA engineer plan request (target: < 300ms p95):**
```
Plugin ──▶ GET /v1/facts/class?symbolFqn=...
       ──▶ Redis hit? ──yes──▶ return <50ms
                   ──no──▶ Postgres CTE query (<100ms) ──▶ write Redis ──▶ return
```

### 2.3 API Contracts (Phase 1)

**Standard response envelope:**

```json
{
  "schemaVersion": "1.0",
  "indexedAt": "2026-05-21T10:05:00Z",
  "commitSha": "abc123",
  "freshnessStatus": "CURRENT | STALE | INDEXING | NOT_INDEXED",
  "data": {}
}
```

**Core endpoints** (see [openapi.yaml](openapi.yaml) for full list):

| Method | Path | Description | Cached | Shipped |
|--------|------|-------------|--------|---------|
| `GET` | `/v1/facts/class` | Facts for a class by `symbol_fqn` | Yes | Yes |
| `GET` | `/v1/facts/outbound` | Outbound calls for a service | Yes | Yes |
| `GET` | `/v1/facts/by-file` | Symbols for file paths | Yes | Yes |
| `GET` | `/v1/graph/reachability` | Transitive service/class reachability | Yes | Yes |
| `GET` | `/v1/graph/impact` | Reverse reachability (impact set) | Yes | Yes |
| `GET` | `/v1/graph/neighborhood` | Depth-1 direct dependencies | Yes | Yes |
| — | `/v1/graph/cross-service` | Cross-service boundary | — | **Internal only** |
| `GET` | `/v1/graph/shared-type` | Canonical shared type by `symbol_fqn` | Yes | Yes |
| `GET` | `/v1/graph/type-fanout` | Services consuming a shared type | Yes | Yes |
| `GET` | `/v1/impact/pr` | PR impact analysis | Yes (`impact:pr`) | Yes |
| `GET` | `/v1/services/{id}/description` | LLM service description | Yes | Yes |
| `GET` | `/v1/status/{serviceId}` | Index job status + freshness | No | Yes |
| `POST` | `/registry/services` | Register a service | — | Yes |
| `GET` | `/registry/services` | List registered services | — | Yes |
| `GET/PATCH/DELETE` | `/registry/services/{id}` | Get / update / disable | — | Yes |
| `POST` | `/admin/index/*`, `/admin/discover` | Admin triggers | — | Yes |
| — | `/v1/gaps` | Test gap report | — | **Planned (P12)** |

**Freshness status matrix:**

| Status | Condition | HTTP (service-scoped queries) |
|--------|-----------|-------------------------------|
| `CURRENT` | `indexed_at` within configured stale threshold | 200 |
| `STALE` | `indexed_at` beyond threshold; last known facts still returned | 200 |
| `INDEXING` | Job in-flight in Kafka for this serviceId | **202** |
| `NOT_INDEXED` | No facts row found for serviceId | **404** |

`GET /v1/status/{serviceId}` always returns 200 with envelope. Shared helper: `FreshnessHttp` — see [TestSeer_REST_API_Design.md](TestSeer_REST_API_Design.md) §6.

**Error responses (P16 — `ApiError` JSON):**

| Scenario | HTTP | `error` enum |
|----------|------|--------------|
| ServiceId unknown to registry (commands) | 404 | `NOT_FOUND` |
| Service never indexed (queries) | 404 | _(ResponseEnvelope with `NOT_INDEXED`)_ |
| Index job in-progress (queries) | 202 | _(ResponseEnvelope with `INDEXING`)_ |
| Postgres / LLM unavailable | 503 | `SERVICE_UNAVAILABLE` |
| Unregistered repo webhook | 200 | (silent drop, logged) |
| Invalid webhook signature | 401 | _(plain webhook response — not ApiError)_ |
| Registry field validation | 400 | `VALIDATION_ERROR` |
| Duplicate service registration | 409 | `CONFLICT` |
| Duplicate in-flight index job | 409 | `CONFLICT` |

### 2.4 Storage Choices

| Store | Purpose | Why |
|-------|---------|-----|
| **Postgres (Cloud SQL)** | Structured facts, graph nodes/edges, registry, job tracking | Recursive CTEs for graph traversal (Phase 0 spike: ≤ 3ms p95); single operational dependency; transactional |
| **MongoDB Atlas** | Raw `ParsedModel` / AST fragments | Semi-structured; not queried on hot path; reserved for debugging and future re-processing |
| **Redis (Memorystore)** | Query API response cache | Sub-50ms reads on hot path; event-driven invalidation via Pub/Sub |
| **Confluent Cloud Kafka** | Job queue with priority separation | At-least-once delivery; offset control enables crash-safe dual-write |
| **GCP Pub/Sub** | One-way push notifications to plugin | Native GCP; no infra to manage; IntelliJ can subscribe directly |

---

## 3. Deep Dive

### 3.1 Data Model

#### Postgres — Fact Key Structure

All fact rows are keyed by `org_id / repo / service_id / commit_sha / symbol_fqn`. This partitioning ensures:
- No cross-org collision
- Both baseline snapshot (`snapshot_type = BASELINE`) and incremental deltas (`snapshot_type = DELTA`) coexist per service
- Consumer always reads: latest `BASELINE` + all `DELTA` rows with `indexed_at > baseline.indexed_at`

```
symbol_facts
  org_id          VARCHAR(100)   ← org partition key
  service_id      VARCHAR(255)   ← FK to service_registry
  commit_sha      VARCHAR(40)    ← freshness anchor
  symbol_fqn      VARCHAR(500)   ← canonical class/method identifier
  snapshot_type   VARCHAR(10)    ← BASELINE | DELTA
  indexed_at      TIMESTAMPTZ    ← consumer freshness check
  fact_schema_version VARCHAR(10) ← breaking change guard
```

#### Graph Node/Edge Structure

```
graph_nodes
  id          VARCHAR(255)   ← "{orgId}/{service}/{nodeType}/{symbol_fqn}"
  org_id      VARCHAR(100)
  service     VARCHAR(255)
  module_type VARCHAR(50)    ← 'service' | 'library'
  node_type   VARCHAR(50)    ← SERVICE | CLASS | ENDPOINT | SHARED_TYPE
  symbol_fqn  VARCHAR(500)

graph_edges
  from_node   VARCHAR(255)   → graph_nodes.id
  to_node     VARCHAR(255)   → graph_nodes.id
  edge_type   VARCHAR(50)    ← CALLS | DEPENDS_ON | OUTBOUND_TO | USES_TYPE
  confidence  FLOAT
  evidence_source VARCHAR(50)
```

**Edge semantic map:**

| Edge | From → To | Used for |
|------|-----------|----------|
| `CALLS` | Service → Service | Service call graph, transitive reachability |
| `DEPENDS_ON` | Class → Class | Constructor/field dependency chain |
| `OUTBOUND_TO` | Endpoint → Endpoint | Cross-service HTTP stub synthesis |
| `USES_TYPE` | Service/Class → SharedType | Shared DTO impact analysis |

#### MongoDB — ParsedModel Document

```json
{
  "_id": "{orgId}/{repo}/{serviceId}/{commitSha}",
  "orgId": "...",
  "serviceId": "...",
  "commitSha": "...",
  "indexedAt": "...",
  "changedFiles": [...],
  "models": [{
    "filePath": "...",
    "classFqn": "...",
    "rawAst": {},
    "annotations": [],
    "constructorParams": [],
    "outboundCalls": []
  }]
}
```

Write path: `replaceOne({_id: ...}, doc, upsert: true)` — idempotent, safe for retry.

---

### 3.2 Analysis Worker Processing Pipeline

```
1. Consume IngestionJob from Kafka (auto-commit OFF)
2. Fetch changed .java files from GitHub Contents API
3. Parse with JavaParser + SymbolSolver (per-file)
   ├── On parse error → emit UnsupportedConstructFact; continue
   └── On success → build ParsedModel
4. Extract from ParsedModel:
   ├── @{Get,Post,Put,Delete,Patch,Request}Mapping → symbol_facts (ENDPOINT)
   ├── Constructor params + @Autowired fields → DEPENDS_ON edges
   ├── RestClient/WebClient/RestTemplate/Feign → outbound_call_facts
   └── Peripheral signals → peripheral_facts (Tier 1/2/3)
5. Normalise to FactBatch (schema version = "1.0")
6. Atomic dual-write (Postgres transaction + MongoDB replaceOne):
   ├── INSERT symbol_facts ON CONFLICT DO UPDATE
   ├── INSERT outbound_call_facts ON CONFLICT DO UPDATE
   ├── INSERT peripheral_facts ON CONFLICT DO UPDATE
   ├── DELETE old graph_edges for changed nodes + INSERT new edges
   └── MongoDB replaceOne ParsedModel
7. Commit Kafka offset (ONLY after step 6 completes)
8. Publish IndexCompleteEvent to GCP Pub/Sub
```

**Peripheral detection rules:**

| Tier | Signal | Prerequisite emitted |
|------|--------|---------------------|
| 1 | `@KafkaListener`, `RedisTemplate`, `@Document`, JPA + Postgres dialect, `AmazonS3`, `@RabbitListener` | Direct Testcontainers / LocalStack recommendation |
| 2 | Oracle/SQL Server JDBC driver, ambiguous JDBC URL (env var reference) | "Verify before using Testcontainers" + detected signal |
| 3 | Spring Cloud Config / Vault datasource, proprietary client (rule pack match), dynamic datasource routing | "Manual setup required — declare in `.testseer/config.yml`" + reason code |

**Idempotency guarantee:** All Postgres inserts use `ON CONFLICT DO UPDATE`; MongoDB uses `replaceOne` with upsert. A job replayed by Kafka (after a crash before offset commit) produces the same final state.

---

### 3.3 Caching Strategy

**Pattern:** Cache-aside (lazy population).

```
GET /v1/graph/reachability?serviceId=X
    │
    ├── Check Redis key: testseer:{orgId}:{repo}:{serviceId}:reachability:{params_hash}
    │       HIT  ──▶ return (target < 50ms)
    │       MISS ──▶ query Postgres CTEs (target < 100ms)
    │                └── write to Redis (TTL: 1 hour)
    │                └── return (< 300ms p95 total)
```

**Invalidation — event-driven (primary):**
- Analysis worker publishes `IndexCompleteEvent{orgId, repo, serviceId}` to Pub/Sub
- Query API Pub/Sub consumer deletes all Redis keys matching `testseer:{orgId}:{repo}:{serviceId}:*`
- Plugin Pub/Sub subscriber shows "fresh index available" banner (no polling)

**TTL (safety net only):** 1 hour. Keys that survive an invalidation event (e.g. missed Pub/Sub message during transient outage) expire automatically.

**Not cached:**
- `GET /v1/status/{serviceId}` — always reads live `indexed_at` from `analysis_runs` table
- Registry CRUD endpoints — write-path operations
- `freshnessStatus` and `indexedAt` fields — always sourced from Postgres even on cache hit (injected into the response envelope after cache retrieval)

---

### 3.4 Queue and Event Design

**Kafka topic configuration:**

| Topic | Partitions | Replication | Retention | Consumer Group |
|-------|-----------|-------------|-----------|----------------|
| `testseer.jobs.pr` | 12 | 3 | 24h | `testseer-workers-pr` |
| `testseer.jobs.batch` | 6 | 3 | 72h | `testseer-workers-batch` |
| `testseer.jobs.dlq` | 3 | 3 | 7d | (monitored only) |

**GCP Pub/Sub (cache invalidation + IDE notifications, optional locally):**

| Topic | Subscription | Consumer | Purpose |
|-------|--------------|----------|---------|
| `testseer-index-complete` | `testseer-index-complete-sub` | `CacheInvalidationListener` | Cross-replica Redis eviction (`PUBSUB_ENABLED=true`) |

Index complete also invalidates Redis in-process via `IndexCompleteNotifier` on every replica that processes the job.

**Job envelope:**

```json
{
  "jobId": "uuid",
  "jobType": "PR | PUSH | NIGHTLY",
  "orgId": "example-org",
  "repo": "order-service",
  "serviceId": "svc-orders-001",
  "commitSha": "abc123",
  "changedFiles": ["src/main/java/..."],
  "prNumber": 42,
  "enqueuedAt": "2026-05-21T10:00:00Z",
  "attempt": 1
}
```

**Priority enforcement mechanism:**
- Two separate consumer groups with separate GKE deployments — no shared thread pool
- `testseer-workers-pr`: 4–16 replicas (HPA on `testseer.jobs.pr` consumer lag)
- `testseer-workers-batch`: 1–4 replicas (HPA on `testseer.jobs.batch` consumer lag; scales down to 1 during business hours)
- PR worker pods get higher CPU/memory requests than batch workers

**Retry with backoff:**

| Attempt | Delay | Action |
|---------|-------|--------|
| 1 | Immediate | Normal processing |
| 2 | 30 seconds | Re-enqueue with `attempt: 2` |
| 3 | 5 minutes | Re-enqueue with `attempt: 3` |
| 4+ | — | Move to `testseer.jobs.dlq`; fire alert |

---

### 3.5 Error Handling

**Webhook Receiver:**

| Error | Response | Behaviour |
|-------|----------|-----------|
| Invalid signature | 401 | Log; drop; no Kafka publish |
| Unregistered repo | 200 | Log; drop silently (prevents GitHub retry storm) |
| Kafka publish failure | 500 | GitHub retries automatically |
| Registry lookup failure | 500 | Log; return 500 to trigger GitHub retry |

**Analysis Worker:**

| Error | Behaviour |
|-------|-----------|
| GitHub API unavailable | Do not commit offset; Kafka redelivers |
| JavaParser exception on single file | Emit `UnsupportedConstructFact`; continue processing remaining files |
| JavaParser exception on entire job | Fail job; increment `attempt`; retry |
| Postgres write failure | Rollback transaction; do not commit offset; retry |
| MongoDB write failure | Rollback Postgres; do not commit offset; retry |
| Max retries exceeded | Publish to DLQ; mark `analysis_runs.status = DLQ` |

**Query API:**

| Error | Response | Behaviour |
|-------|----------|-----------|
| Redis unavailable | — | Bypass cache; query Postgres directly |
| Postgres unavailable | 503 `STORE_UNAVAILABLE` | Do not return stale cached data for status endpoints |
| ServiceId not in registry | 404 `NOT_REGISTERED` | Include hint: "Register via POST /registry/services" |
| Job in-flight | 202 `INDEXING` | Return last known facts alongside status |

---

## 4. Scale and Reliability

### 4.1 Load Estimation

**Assumptions (single-tenant, 100-repo org):**
- 100 services, ~15 active PRs/day average, peak 50 concurrent PRs
- Average 20 changed files per PR → ~1,000 file-parses/day normal; ~5,000 burst
- Nightly baseline: 100 services × ~200 files avg → ~20,000 file-parses/night
- Query API: 50 QA engineers × ~10 plan requests/day → ~500 queries/day; burst ~20 req/s during sprint end

**Postgres sizing:**
- `symbol_facts`: ~100 services × ~2,000 symbols avg × 50 commits retained ≈ 10M rows
- `graph_nodes`: ~100 services × ~300 nodes avg ≈ 30,000 rows (tiny)
- `graph_edges`: ~100 services × ~600 edges avg ≈ 60,000 rows (tiny)
- Cloud SQL db-custom-4-16384 (4 vCPU, 16 GB RAM) sufficient for Phase 1; scale vertically if p95 exceeds 50ms

**Redis sizing:**
- Cached responses: ~500 unique (serviceId × queryType) combinations × avg 10 KB ≈ 5 MB hot working set
- Memorystore Basic tier (1 GB) is 200× headroom for Phase 1

**Kafka throughput:**
- Normal: ~15 jobs/hour on PR topic; Confluent Cloud handles this trivially
- Peak: 50 concurrent PRs × 5 services/repo avg = 250 jobs in minutes; 12-partition PR topic, 16 consumer replicas handles ~5 jobs/s easily

### 4.2 Horizontal Scaling

| Component | Scaling axis | HPA trigger | Min/Max replicas |
|-----------|-------------|-------------|-----------------|
| `webhook-receiver` | Stateless HTTP | CPU / request rate | 2 / 6 |
| `analysis-worker-pr` | Kafka consumer | `testseer.jobs.pr` consumer lag | 4 / 16 |
| `analysis-worker-batch` | Kafka consumer | `testseer.jobs.batch` consumer lag | 1 / 4 |
| `query-api` | Stateless HTTP | CPU / request rate | 2 / 8 |

**Scaling isolation:** `testseer-ingestion` and `testseer-query` namespaces have no resource sharing. An ingestion spike (e.g. 50 PRs merging at once) cannot starve Query API pods of CPU/memory.

**Database scaling:**
- Postgres: Cloud SQL read replicas for Query API read path (not Phase 1; add when P95 > 50ms)
- Redis: Memorystore scales vertically; Phase 1 load does not require clustering
- Kafka: Confluent Cloud elastic; partition count set conservatively (12 PR / 6 batch); increase without downtime

### 4.3 Failover and Redundancy

| Layer | Failure mode | Recovery |
|-------|-------------|---------|
| `webhook-receiver` pod crash | 2 replicas; GitHub retries webhook on 5xx | Zero job loss |
| `analysis-worker` pod crash mid-parse | Kafka offset not committed; automatic redeliver | Zero fact loss; duplicate parse harmless |
| `analysis-worker` write failure | Postgres rollback + no offset commit; retry | Zero inconsistency |
| `query-api` pod crash | 2+ replicas; GKE restart; Redis cache absorbs traffic | < 1s interruption |
| Redis unavailable | Query API bypasses cache; reads Postgres directly | Latency degrades but service stays up |
| Postgres unavailable | Query API returns 503; workers buffer in Kafka | No data loss; facts persisted when Postgres recovers |
| Kafka broker unavailable (Confluent Cloud SLA ≥ 99.95%) | Webhook Receiver returns 500; GitHub retries | At most 60s retry delay |
| Pub/Sub message loss | Redis TTL (1h) eventually invalidates stale cache | At most 1h stale cache; no fact inconsistency |

**No single point of failure in the query path:** Redis → Postgres fallback is automatic with no code change required.

### 4.4 Monitoring and Alerting

**Key metrics to instrument:**

| Signal | Alert threshold | Meaning |
|--------|-----------------|---------|
| `testseer.jobs.pr` consumer lag | > 500 messages for > 2 min | Worker backlog; scale up or investigate |
| `testseer.jobs.dlq` message count | > 0 | Persistent failure; needs investigation |
| Analysis worker P95 parse duration | > 30s | JavaParser slowdown; likely complex annotation processor |
| Query API P95 latency | > 250ms | Approaching SLO; investigate cache miss rate |
| Redis cache miss rate | > 70% | Cache ineffective; check invalidation logic |
| `analysis_runs` rows with `status = FAILED` | > 5 in 15 min | Worker error spike |
| Postgres connection pool saturation | > 90% utilisation | Add read replica or tune pool |
| Pub/Sub subscription delivery lag | > 5 min | Index-complete events not reaching Query API |

**Tracing:** Propagate `jobId` through Kafka job envelope, Postgres `analysis_runs.job_id`, Pub/Sub event, and Query API response `X-Job-Id` header. Enables end-to-end trace from GitHub push to plugin notification.

---

## 5. Trade-off Analysis

### 5.1 Postgres Recursive CTEs vs. Neo4j

| Dimension | Postgres CTEs | Neo4j |
|-----------|--------------|-------|
| Phase 0 p95 (all queries, 40 services) | ≤ 3ms | 7–14ms |
| Operational overhead | Zero (already have Cloud SQL) | Separate GKE deployment, backup policy, upgrade cycle |
| Familiarity | SQL — widely known | Cypher — specialist knowledge |
| Phase 1 scale | Handles 100 services with headroom | Overkill |
| Scale ceiling | ~500 services before CTE planner struggles | Virtually unlimited |
| **Decision** | **Selected** | Rejected; revisit if portfolio > 200 services |

### 5.2 Kafka Priority Separation (Two Topics vs. One Topic + Priority Field)

| Dimension | Two topics (selected) | One topic + priority field |
|-----------|-----------------------|---------------------------|
| PR jobs blocked by batch? | Never — separate consumer groups | Yes — batch jobs ahead in queue can delay PR jobs |
| Complexity | Two consumer deployments | Single deployment, simpler ops |
| HPA independence | Yes — each topic has its own lag metric | No — shared lag metric |
| **Decision** | **Selected** — priority SLO requires it | Rejected |

### 5.3 Dual-Write (Postgres + MongoDB) vs. Postgres Only

| Dimension | Dual-write (selected) | Postgres only |
|-----------|-----------------------|---------------|
| Re-processing without GitHub | Yes — MongoDB has full AST | No — must re-fetch from GitHub |
| Debugging parse output | Yes — inspect raw ParsedModel | Limited to structured facts |
| Write complexity | Atomic, two stores; offset after both | Simpler |
| Failure surface | MongoDB write failure rolls back Postgres | N/A |
| **Decision** | **Selected** — future re-processing and debugging value justifies complexity |

### 5.4 Event-Driven Cache Invalidation vs. TTL Only

| Dimension | Event-driven (selected) | TTL only |
|-----------|--------------------------|---------|
| Freshness after index | Seconds (Pub/Sub latency) | Up to TTL (1 hour) |
| Complexity | Pub/Sub consumer in Query API | None |
| Correctness on Pub/Sub outage | Falls back to TTL (1h stale max) | Already correct |
| **Decision** | **Selected** — 5-minute freshness SLO requires event-driven invalidation |

### 5.5 Per-Service Job Decomposition vs. Per-Repo Jobs

| Dimension | Per-service (selected) | Per-repo |
|-----------|------------------------|---------|
| Monorepo efficiency | Index only changed services | Re-index all services on any change |
| Job granularity | Requires registry `source_roots` mapping | Simpler; no mapping needed |
| 40-service monorepo, 3 files changed | 1 job (1 service re-indexed) | 40 jobs |
| **Decision** | **Selected** — critical for monorepo efficiency at target scale |

### 5.6 Registry Embedded in Query API vs. Separate Registry Service

| Dimension | Embedded (selected) | Separate service |
|-----------|---------------------|-----------------|
| Phase 1 registry write frequency | Low (engineering lead config) | Same |
| Operational overhead | Zero additional deployment | Extra deployment, port, ingress rule |
| Extraction path | Extract if write load grows | Start there if anticipated high throughput |
| **Decision** | **Selected** for Phase 1; extract if automated onboarding drives high write throughput |

---

## 6. What to Revisit as the System Grows

| Trigger | Revisit |
|---------|---------|
| Portfolio > 200 services OR graph traversal P95 > 50ms | Evaluate Neo4j or dedicated graph layer |
| Multiple tenants onboarded | Enforce `org_id` row-level security; consider schema-per-tenant |
| Automated repo onboarding (CI-driven) | Extract Registry into a dedicated service with its own write path |
| > 1,000 index jobs/hour sustained | Add Postgres read replicas for Query API; consider CQRS |
| Kafka consumer lag repeatedly > 500 messages | Increase partition count on `testseer.jobs.pr` and replicas |
| MongoDB read queries from Query API | Add Atlas indexes on `{orgId, serviceId, commitSha}` |
| Plugin PSI surface added | Worker output contract must remain semantically equivalent (enforced by user story) |
| `fact_schema_version` bump required | Run migration playbook: snapshot update + CHANGELOG + fan-out to all consumers |

---

## 7. Open Questions (Phase 1 Blockers)

| Question | Owner | Impact if unresolved |
|----------|-------|---------------------|
| Stale index threshold — default 1 hour? | QA Engineering leads | `freshnessStatus = STALE` trigger in Query API |
| GitHub App (JWT auth) vs. webhook secret only | Engineering | Webhook Receiver signature validation implementation |
| Nightly scheduler: Cloud Scheduler vs. Kafka-native (scheduled message) | Engineering | Batch job trigger implementation; Cloud Scheduler is simpler |
| MongoDB Atlas tier and region — same region as GKE? | Platform | Dual-write latency; different-region adds 10–50ms per write |
