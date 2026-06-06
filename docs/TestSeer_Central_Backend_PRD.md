# TestSeer Central Intelligence Backend — Product Requirements Document

> **Status:** Historical — requirements largely implemented in `testseer-backend/`  
> **Last verified:** 2026-06-05  
> **Canonical for:** Appendix A (Postgres vs Neo4j spike decision and benchmark table)  
> **Live status:** [CURRENT_STATUS.md](../../docs/CURRENT_STATUS.md)

**Feature:** Central Intelligence Backend for 100+ Repo Scale
**Author:** TestSeer Product
**Date:** 2026-05-21

---

## Implementation status (2026-06-05)

| Must-have area | Status |
|----------------|--------|
| Service registry + webhook ingestion | **Done** |
| Analysis workers + dual-write (Postgres + MongoDB) | **Done** |
| Graph projection (Postgres CTEs) | **Done** — decision: Postgres over Neo4j (Appendix A) |
| Query API + Redis cache + freshness envelope | **Done** |
| PR impact analysis (`/v1/impact/pr`) | **Done** |
| MCP surface (Cursor) | **Done** — `testseer-mcp/` |
| Admin discover / local index | **Done** |
| Test gap API (`/v1/gaps`) | **Not started** (P12) |
| Surface integration — IntelliJ/CLI API-backed mode | **Not started** |
| Pub/Sub → plugin push notifications | **Not started** |
| Nightly scheduler | **Not started** (job type exists) |
| Calibration dashboard | **Not started** |

---

## Problem Statement

QA engineers today run TestSeer locally, meaning every test plan generation triggers a full parse of the target repository from scratch. Across a portfolio of ~100 repositories — some with 40+ microservices — this is slow, inconsistent, and deaf to cross-service dependencies. When a service team makes a breaking change upstream, the QA engineer on the downstream service has no way to know their test plan is now stale. The cost of not solving this is duplicate analysis work per engineer, test plans that don't reflect real dependency topology, and confidence erosion in the tool.

---

## Goals

1. **Reduce time-to-plan for QA engineers** — P95 test plan generation latency drops below 300ms for API-backed queries (vs. multi-second local parses today).
2. **Keep plans current without engineer action** — code changes on default branches and active PRs are reflected in test plans within 5 minutes, with no manual re-trigger required.
3. **Surface cross-service dependency context** — a QA engineer generating a plan for Service A sees relevant facts about downstream Service B calls it makes, even if B lives in a different repo.
4. **Enable portfolio-level calibration** — classification accuracy, stub inference quality, and scenario usefulness are tracked across all indexed repos, not just per local run.
5. **Support local-first fallback** — QA engineers in air-gapped or policy-sensitive environments can still run TestSeer fully locally; the backend is an enhancement, not a hard dependency.

---

## Non-Goals

1. **Not a general-purpose code search or indexing platform.** The backend stores TestSeer-specific facts (endpoints, outbound calls, dependencies, layer classifications) — not raw source code or full AST trees.
2. **Not a real-time collaborative editing surface.** The backend does not mediate concurrent plan editing or plan versioning between engineers. Plans are generated on demand and owned by the requesting engineer.
3. **Not responsible for running tests or evaluating test quality (TestGuard's job).** The backend feeds test *planning*. Evaluating already-written tests is TestGuard's domain; the two share the code intelligence layer but have separate prediction engines and output schemas.
4. **Not a multi-language indexer in v1.** The initial backend indexes Java services only. Python, Go, and Kotlin support are explicitly out of scope until the Java pipeline is stable and calibrated.
5. **Not a public API.** External integrations beyond the IntelliJ plugin and CLI/Maven surface are out of scope. The query API is an internal service boundary, not a developer platform API.
6. **Not a multi-SCM integration in v1.** The ingestion orchestrator implements GitHub webhook payloads only. GitLab and Bitbucket webhook formats are out of scope for v1.

---

## User Stories

### QA Engineer — Primary Persona

**Plan generation (speed and freshness)**
- As a QA engineer, I want my test plan to appear in under 2 seconds when I trigger generation from IntelliJ, so that it doesn't break my flow when switching context to a new class.
- As a QA engineer, I want the test plan to reflect the current state of the codebase without me having to manually re-index, so that I don't generate plans against stale code after a merge.
- As a QA engineer, I want the plugin to show me when the indexed facts for a class are stale (e.g., last indexed >1 hour ago on an active repo), so that I can decide whether to trust the plan or trigger a local refresh.

**Cross-service context**
- As a QA engineer testing an API controller that calls two downstream services, I want the generated stub specs to include realistic path/method/response shapes from those downstream services' actual endpoints, so that I'm not writing stub matchers from memory.
- As a QA engineer, I want to see a "change impact" note in my plan when an upstream dependency has changed since my last test run, so that I know which scenarios may need revisiting.

**Trust and transparency**
- As a QA engineer, I want every fact in my test plan to show its evidence source (PSI, JavaParser, or inferred) and confidence score, so that I know how much to trust each generated requirement.
- As a QA engineer, I want the plan to clearly distinguish "high-confidence" facts (directly observed in code) from "inferred" facts (extrapolated from patterns), so that I know where to apply extra scrutiny.

**Offline / air-gapped use**
- As a QA engineer working in an air-gapped environment, I want TestSeer to fall back to local-only parsing with no degradation in output format, so that I'm not blocked when I can't reach the central backend.

### Engineering Lead — Secondary Persona

**Policy and configuration**
- As an engineering lead, I want to configure which repositories and services are indexed, so that we don't inadvertently index repos with security or IP restrictions.
- As an engineering lead, I want to set team-level access controls so that QA engineers can only see cross-service dependency facts for repos their team is authorized to access.

**Calibration and quality**
- As an engineering lead, I want a calibration dashboard showing classifier accuracy and stub inference quality across my team's repos, so that I can identify where the tool is underperforming and needs rule-pack tuning.

---

## Requirements

### Must-Have (P0) — Core Backend Platform

**Repository and Service Registry**
- [ ] Registry stores `org/repo/service` boundaries with build tool, language version, source roots, and test roots
- [ ] Shared libraries are registered as a subset of their parent repo, not as independent registry entries — they inherit `org_id/repo` and are distinguished by a `module_type: library` flag; endpoint and outbound call facts are not expected for library modules but type and interface facts are fully indexed
- [ ] Supports manual overrides for non-standard monorepo layouts
- [ ] Registry entries can be enabled/disabled without data deletion
- [ ] Schema uses `org_id` as a first-class partition key from day one, so that multi-tenant expansion does not require a schema migration
- *Acceptance criteria:* A new repo can be registered and immediately queued for ingestion; disabling a repo stops future index jobs without purging historical facts; shared library repos index type/interface facts and are queryable as dependency sources by service repos

**Ingestion Orchestrator**
- [ ] Triggers index jobs on: GitHub webhook events (PR updates, default-branch pushes), and scheduled nightly refresh
- [ ] Publishes jobs to Kafka; workers consume via consumer groups — allows horizontal scaling and replayability without job loss on worker failure
- [ ] Uses separate Kafka topics for priority tiers: active PR jobs and nightly batch jobs, so PR jobs are never starved by batch load
- [ ] Assigns jobs per service (not per repo monolithically) to enable parallel worker processing
- [ ] Failed jobs are retried with backoff; unrecoverable jobs move to a dead-letter topic and are surfaced in the calibration dashboard
- *Acceptance criteria:* A push to a repo's default branch triggers an incremental index job within 60 seconds; PR jobs complete within 5 minutes for repos up to 50k LoC; worker pod restart does not cause job loss

**Analysis Workers**
- [ ] Parse Java source with JavaParser + SymbolSolver
- [ ] Extract and store: class/method annotations, constructor and field dependencies, endpoint mappings (`@RequestMapping`, `@GetMapping`, etc.), outbound calls (`RestClient`, `WebClient`, `RestTemplate`, Feign), and inferred protocols
- [ ] Detect peripheral dependencies from source signals and classify each into one of three confidence tiers:

  **Tier 1 — High confidence (detectable from source, recommend directly):**
  - Kafka: `@KafkaListener`, `@EnableKafka`, `KafkaTemplate`, `spring.kafka.bootstrap-servers`
  - Redis: `@Cacheable`, `RedisTemplate`, `StringRedisTemplate`, `spring.redis.host`
  - MongoDB: `@Document`, `@MongoRepository`, `MongoTemplate`, `spring.data.mongodb.uri`
  - Postgres / MySQL: JPA `@Entity`, `@Repository`, Hibernate dialect, JDBC driver class
  - AWS (S3, SQS): `AmazonS3`, `S3Client`, `SqsClient` — LocalStack Testcontainers recommended
  - RabbitMQ: `@RabbitListener`, `RabbitTemplate`
  → Emit Testcontainers or LocalStack prerequisite directly

  **Tier 2 — Possible on-prem (ambiguous signals, flag for verification):**
  - Oracle JDBC driver detected → likely on-prem; SQL Server driver detected → verify
  - Postgres/MySQL driver present but JDBC URL is externalized (env var, config server reference) → cannot confirm environment
  → Emit "verify before using Testcontainers" prerequisite with detected signal and rationale

  **Tier 3 — Unknown (emit manual setup required):**
  - Config fully externalized via Spring Cloud Config or Vault — peripheral type cannot be resolved from source; emit "config is externalized — declare peripheral in `.testseer/config.yml`" as the prerequisite
  - Proprietary internal client detected via rule pack — no standard signals available
  - Dynamic datasource routing detected at runtime
  → Emit "manual setup required" prerequisite with explicit reason code; never recommend Testcontainers

- [ ] Teams can override Tier 3 defaults by declaring peripheral topology explicitly in `.testseer/config.yml` per service — overrides specify type, tier, and a custom prerequisite note; this is the recommended path when config is externalized rather than having TestSeer query the config server at index time

- [ ] Rule packs are extensible for proprietary internal clients — teams can teach TestSeer org-specific peripheral patterns (e.g. `InternalOracleClient`, `LegacyMQWrapper`) via custom `detect` rules that map to Tier 2 or Tier 3 classifications with a custom prerequisite message
- [ ] Every peripheral fact record includes: detected peripheral type, confidence tier, detection signals that fired, and fixture strategy recommendation
- [ ] Write structured facts to PostgreSQL and raw parsed models / AST fragments to MongoDB; both writes must succeed or the job is retried
- [ ] Compute full transitive dependency graphs (unbounded depth)
- [ ] Normalize output to schema-versioned fact records with `evidence_source`, `confidence`, `schema_version`, and `indexed_at`
- *Acceptance criteria:* Oracle/SQL Server drivers produce Tier 2 "verify" prerequisites, never direct Testcontainers recommendations; externalized config produces Tier 3 "manual setup required"; high-confidence peripherals (Kafka, Redis, MongoDB) produce direct Testcontainers/LocalStack prerequisites; proprietary clients defined in rule packs produce the configured prerequisite message

**Fact Store (PostgreSQL)**
- [ ] Primary store is PostgreSQL; stores facts keyed by `org_id/repo/service/commit_sha/symbol_fqn`
- [ ] Maintains full baseline snapshots and incremental deltas per changed-file set
- [ ] Schema versioned; breaking changes follow the existing schema contract policy (see [`schema-contract.md`](../../docs/schema-contract.md))
- *Acceptance criteria:* Querying facts for a class returns both the baseline snapshot and any incremental deltas applied since the last full index

**Graph Projection Layer**
- [ ] Materialized edges for fast traversal: `class_depends_on_class`, `method_calls_method`, `service_calls_service`, `endpoint_calls_outbound`
- [x] Storage technology (Postgres recursive CTEs vs. graph-native) decided by Phase 0 spike outcome — **Postgres selected** (Appendix A)
- [ ] Supports unbounded transitive queries across the full dependency graph
- [ ] Supports cross-service boundary traversal — following edges that cross `service_id` partitions (e.g., tracing a `RestClient` call from Service A through to the endpoint definition in Service B)
- [ ] Supports shared type resolution — given a `symbol_fqn` used in Service A, resolve its canonical definition which may live in a shared library repo or common module; provenance disambiguates when the same type name appears in multiple repos
- [ ] Supports type usage fan-out — reverse index from a shared type/DTO definition to all services that consume it, enabling change impact on shared library updates
- *Acceptance criteria:* Full reachability query for a service (unbounded depth) returns in < 100ms; cross-service traversal resolves to canonical shared type definitions with provenance metadata

**Query API**
- [ ] Exposes read endpoints for: class/service dependency graph, outbound call map, transitive impact set, confidence and provenance per fact
- [ ] Uses Redis to cache responses for frequently queried repos and services; cache is invalidated on successful index job completion
- [ ] Returns freshness metadata with every response (`indexed_at`, `commit_sha`)
- [ ] P95 query latency ≤ 300ms under expected load; cache-hit responses target < 50ms
- [ ] Responses are schema-versioned; unknown optional fields are ignored by consumers
- *Acceptance criteria:* IntelliJ plugin and CLI can query the API and receive structured facts; cache hit rate ≥ 70% for active repos under normal usage; local fallback activates transparently on API error

**Surface Integration — API-backed mode**
- [ ] IntelliJ plugin queries central facts when API is reachable, enriching test plans with cross-service context
- [ ] Plugin subscribes to GCP Pub/Sub "index ready" events so it can proactively notify the engineer when a fresher index is available — no polling required
- [ ] CLI/Maven defaults to API-backed mode; `--local` flag forces local-only
- [ ] Plugin shows "stale index" warning when `indexed_at` exceeds a configurable threshold
- *Acceptance criteria:* End-to-end: push to default branch → worker indexes → Pub/Sub event fires → plugin surfaces "fresh index available" within 5 minutes; no polling loop required in plugin

**Local Fallback**
- [ ] When API is unreachable (timeout, network error, auth failure), IntelliJ and CLI fall back to local parsing with no output format difference beyond a banner/log line
- [ ] Fallback produces equivalent output format to API-backed mode, with `evidence_source: local` annotation on facts
- *Acceptance criteria:* Toggling API connectivity mid-session produces no plan format differences; fallback is deterministic and fully functional

### Nice-to-Have (P1) — Quality and Operations

**Calibration and Quality Service**
- [ ] Tracks per-repo and portfolio-level metrics: classifier accuracy, stub inference precision/recall, skeleton edit distance, adoption metrics
- [ ] CI gate fails if accuracy drops below defined thresholds
- [ ] Dashboard shows metric drift over time

**Incremental Freshness**
- [ ] PR updates visible within 2–5 minutes for active repos (≤ 50k LoC services)
- [ ] Nightly full consistency sweep corrects any incremental drift

**Access Controls**
- [ ] Team-level read access controls for cross-repo facts
- [ ] Least-privilege GitHub tokens per integration

### Future Considerations (P2) — Advanced Intelligence

- **Cross-repo change impact ranking:** When Service A's API changes, surface a ranked list of downstream services whose test plans may need regeneration. *Deferred — requires stable graph + usage telemetry to build a useful relevance model.*
- **Similar-test retrieval and dedup:** Query the index for existing tests covering similar scenarios to avoid redundant coverage. *Deferred — needs embedding index and RAG layer (v0.3 roadmap item).*
- **Knowledge graph augmentation:** Ingest docs, PRDs, and incident histories alongside code facts to enrich scenario generation context. *Deferred — complex ingestion pipeline; value unclear until core fact indexing is stable.*
- **Multi-tenant expansion:** Add cross-tenant data isolation and tenant-level billing/quota controls. *Single-tenant now, but `org_id` partition key is required from day one to make this migration non-breaking.*
- **GitLab / Bitbucket webhook support:** Extend the ingestion orchestrator to non-GitHub SCM platforms. *Deferred until GitHub pipeline is stable.*
- **Python / Go / Kotlin support:** Extend analysis workers to non-Java services. *Deferred until Java pipeline reaches calibration thresholds.*

---

## Infrastructure Requirements

### Deployment Model

| Concern | Decision | Notes |
|---|---|---|
| Cloud provider | GCP | All managed services provisioned on GCP |
| Containerization | Docker | All services and workers containerized; images stored in Artifact Registry |
| Orchestration | GKE (Kubernetes) | Workers and query API deployed as separate workloads; scaled independently |
| Ingestion queue | Confluent Cloud (Kafka) on GCP | Separate topics per priority tier (PR jobs vs. nightly batch); consumer groups for worker scaling and fault tolerance |
| Event notification bus | GCP Pub/Sub | Lightweight notifications only — e.g. "index ready" events pushed to plugin/CLI consumers so they don't poll; not used for heavy job queuing |
| Structured fact store | Cloud SQL (PostgreSQL) | Keyed facts, graph projection, registry, analysis run metadata; encryption at rest included |
| Semi-structured fact store | MongoDB | Raw parsed models, AST fragments, and rule pack outputs that don't fit a relational schema; lives alongside Postgres |
| Cache | Redis | Query API response cache for hot repos/services; reduces Cloud SQL read load on frequent lookups |
| Secrets management | GCP Secret Manager | GitHub tokens, database credentials, service-to-service keys |

### Scaling Requirements

- **Analysis workers** scale horizontally on GKE based on Kafka consumer lag — more workers spin up as the queue grows, scale down when idle
- **Query API** scales independently of workers; read load and indexing load must not contend for the same compute
- **Per-repo concurrency caps** configurable in the orchestrator to prevent a single large repo from saturating all workers
- **Nightly batch jobs** run on lower-priority node pools to avoid competing with active PR jobs for capacity

### Reliability Requirements

- Worker pod restarts must not cause job loss — Kafka consumer group offsets are committed only after successful fact store writes
- Dead-letter topic captures unrecoverable jobs (parse failures, schema violations) for manual review without blocking the queue
- Query API must remain available during ingestion spikes — no shared resource contention between the two workloads
- Cloud SQL failover must be automatic; fact store is the source of truth and must not lose committed data

### Security Requirements

- GCP IAM governs service-to-service authentication; no long-lived shared secrets between components
- GitHub webhook payloads validated by signature before job creation
- Fact store network access restricted to GKE cluster VPC; no public endpoint
- PII and secrets redaction applied in analysis workers before facts are written to the store
- Least-privilege GitHub App tokens scoped per repo, not org-wide

---

## Success Metrics

### Leading Indicators (Days–Weeks Post-Launch)

| Metric | Target | Measurement |
|---|---|---|
| P95 query latency | ≤ 300ms | API gateway p95 latency histogram, 7-day rolling |
| Index job success rate | ≥ 99% | Successful jobs / total triggered, per week |
| PR freshness lag | ≤ 5 minutes | Time from push event to index completion, p95 |
| API-backed mode adoption | ≥ 80% of plan generations within 4 weeks of rollout | Plugin telemetry: `evidence_source` distribution |
| Local fallback invocation rate | < 5% of sessions | API error rate from plugin/CLI perspective |

### Lagging Indicators (Weeks–Months Post-Launch)

| Metric | Target | Measurement |
|---|---|---|
| Classifier accuracy (portfolio) | ≥ 90% on labeled corpus | Calibration dashboard, monthly labeled-corpus run |
| Stub inference precision | ≥ 80% on labeled sample | Precision/recall report from calibration runner |
| Skeleton edit distance reduction | 20% fewer manual edits vs. local-only baseline | Sampled QA engineer review at 60-day mark |
| Plan stale-index complaints | < 2 per sprint across pilot teams | Support/Slack triage |
| QA engineer NPS for tool | Improvement vs. v0.1 local-only baseline | Quarterly pulse survey |

---

## Open Questions

### Decided

| Question | Decision |
|---|---|
| **Fact store technology** | PostgreSQL — primary store for all tabular facts |
| **SCM webhook integration** | GitHub only for v1; GitLab/Bitbucket are P2 |
| **Tenant isolation model** | Single-tenant for now; `org_id` partition key required from day one for future expansion |
| **Graph storage layer** | **Postgres recursive CTEs** — decided by Phase 0 spike (see Appendix A). All 9 benchmark queries ran faster on Postgres at 40-service scale; Neo4j adds operational overhead with no performance benefit at this size. |
| **Ingestion queue** | Kafka — provides durable, ordered, replayable job delivery with consumer group semantics for worker scaling |
| **Cloud provider** | GCP |
| **Containerization** | Docker — all services and workers containerized |
| **Orchestration** | Kubernetes (GKE) — horizontal worker scaling, independent scaling of query API and ingestion workers |

### Still Open

| Question | Owner | Blocking? | Notes |
|---|---|---|---|
| **Proprietary client rule pack seed** | Engineering + QA leads | Yes — needed before first pilot | Teams with internal wrapper clients (e.g. `InternalOracleClient`, `LegacyMQWrapper`) must define rule pack entries before TestSeer can classify those peripherals. Need a catalogue of known internal clients to seed the default rule pack |
| **Externalized config handling** | Decided — always Tier 3 by default; teams override via `.testseer/config.yml` peripheral declarations per service. TestSeer does not query the config server at index time (environment ambiguity, security surface, availability dependency) |
| **Shared library registry model** | Decided — libraries are a subset of their parent repo, distinguished by `module_type: library`; inherit `org_id/repo`, no endpoint or outbound call facts expected |
| **Stale index warning threshold** | QA Engineering leads | No — can ship with a 1-hour default | Depends on repo commit velocity; configurable per repo |
| **Telemetry opt-in policy** | Legal / Security | No — can add telemetry post-launch | Aggregate usage telemetry vs. explicit per-engineer opt-in |
| **Rollout strategy** | Engineering Lead | No | Gradual per-team allows calibration before full commitment; all-at-once is operationally simpler |

---

## Timeline

- **Phase 0 (Weeks 1–2): Graph Storage Spike** ✓ COMPLETE
  All 9 benchmark queries implemented and executed against a 500-node / 1,480-edge fixture. Decision: **Postgres recursive CTEs**. Full results and analysis in Appendix A. A circular-topology edge case in the cross-service boundary query was identified, root-caused, and fixed before Phase 1 begins.

- **Phase 1 (Weeks 3–6): Platform MVP**
  Repo/service registry, GitHub webhook ingestion, analysis workers, Postgres fact store (with `org_id` partition key), graph projection layer on chosen storage, first query API endpoints.

- **Phase 2 (Weeks 7–10): Surface Integration**
  IntelliJ plugin and CLI API-backed enrichment, confidence and provenance surfaces, stale-index warnings, strict/partial mode rollout per team.

- **Phase 3 (Weeks 11–14): Reliability and Calibration**
  Calibration dashboards, CI quality gates, drift detection and automatic recalibration jobs, performance tuning for high-traffic repos.

- **Phase 4 (Weeks 15+): Advanced Intelligence**
  Cross-repo change impact ranking, similar-test retrieval, knowledge graph augmentation.

**Critical path:** Phase 0 spike → graph storage decision → Phase 1 fact store schema → Phase 1 query API → Phase 2 surface integration. Each gate must close before the next begins.

---

## Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Graph storage performance degrades as portfolio scales beyond 40-service spike fixture | Phase 0 spike confirmed Postgres p95 < 5ms at 40-service scale; re-benchmark at 200+ services; `org_id` + service partitioning keeps future migration scope bounded if needed |
| Symbol resolution failures in complex multi-module builds | Fallback tiers + explicit failure telemetry; safe-bail with reason codes prevents silent bad output |
| Cost blow-up from full rescans on large repos | Incremental-first strategy; selective deep reindex on explicit request only |
| Trust erosion from weak or stale evidence | Confidence and provenance attached to every fact; stale-index warnings surfaced in plugin UI |
| Classifier drift across the portfolio | Labeled corpus runner in CI; monthly recalibration jobs; gates block release on threshold violations |
| Service boundary ambiguity in monorepos | Registry manual overrides; team-level boundary declarations take precedence over inferred boundaries |
| Shared type `symbol_fqn` collision across repos (same class name in different libraries) | Provenance metadata (`org_id/repo/commit_sha`) disambiguates; shared library registry model decision (open question) defines canonical resolution rules |
| Kafka consumer lag during large batch ingestion starves PR jobs | Separate priority topics with dedicated consumer groups; PR consumer group always gets first claim on available workers |
| GKE worker pod churn during scale-down causes in-flight job loss | Kafka offset committed only after successful fact store write; graceful shutdown hook drains in-flight job before pod terminates |
| Cloud SQL becoming a write bottleneck during peak ingestion | Partition writes by `service_id`; batch fact inserts per worker run; Redis cache absorbs read load; evaluate read replicas if needed |
| Postgres and MongoDB fact writes going out of sync (partial write failure) | Both writes in the same worker transaction scope; if MongoDB write fails, Postgres write is rolled back and job is retried via Kafka |
| Peripheral misclassification (Tier 1 assigned to an on-prem dependency) | Oracle and SQL Server drivers always floor at Tier 2 regardless of other signals; externalized config always floors at Tier 3 — these rules are hardcoded, not rule-pack-overridable, to prevent accidental Testcontainers recommendations for on-prem systems |
| Proprietary internal clients producing silent Tier 3 "unknown" at pilot | Requires seed rule pack entries from QA leads before pilot begins; without them, internal clients silently fall through to "manual setup required" with no specificity |
| Redis cache serving stale facts after a fast successive index | Cache invalidation triggered by Kafka "index complete" event, not TTL alone; TTL serves as a safety net only |
| Graph storage migration needed at scale beyond 100-repo | Spike was run at 40-service / 500-node scale; re-run benchmark annually or when portfolio exceeds 200 services to validate Postgres still meets SLOs |

---

## Appendix A — Phase 0 Graph Storage Spike Results

**Decision: Postgres recursive CTEs**
**Date:** 2026-05-22
**Fixture:** 500 nodes, 1,480 edges across 40 services (5 domains × 8 services, 8 classes and 3 endpoints per service, 4 shared libraries)
**Methodology:** 3 warm-up runs discarded; 20 measured runs per query per store; wall-clock milliseconds

### Benchmark Results

| Query | Description | Postgres avg | Postgres p95 | Neo4j avg | Neo4j p95 | Results |
|---|---|---|---|---|---|---|
| `service_calls_service_forward` | Full reachability: which services does this service transitively call? | 1.7ms | 3ms | 6.1ms | 8ms | 40 |
| `class_depends_on_class_forward` | Full reachability: which classes does this class transitively depend on? | 2.0ms | 3ms | 5.7ms | 7ms | 80 |
| `endpoint_calls_outbound` | Transitive outbound calls from an endpoint | 1.5ms | 2ms | 5.3ms | 7ms | 40 |
| `reverse_reachability` | Impact direction: which nodes are affected if this changes? | 1.7ms | 2ms | 5.9ms | 11ms | 40 |
| `immediate_neighborhood` | Depth-1: direct dependencies (hot path for IntelliJ) | 0.5ms | 1ms | 5.2ms | 7ms | 4 |
| `cross_service_boundary` | Follow call edges across service partitions | 2.1ms | 3ms | 4.7ms | 6ms | 39 |
| `shared_type_resolution` | Resolve `symbol_fqn` to canonical library definition | 0.4ms | 1ms | 4.3ms | 6ms | 1 |
| `type_usage_fan_out` | Which services consume a given shared DTO? | 0.4ms | 1ms | 5.2ms | 7ms | 10 |
| `incremental_edge_update` | Update edges for a single changed file (write) | 1.2ms | 2ms | 11.7ms | 14ms | — |

All queries comfortably under the 100ms SLO target. Postgres wins on every query — 2–11× faster depending on query type.

### Recommendation

**Postgres recursive CTEs. Do not adopt Neo4j.**

At 40-service scale, every Postgres query is well under 5ms p95. Neo4j is 2–11× slower and adds a second managed service (operational complexity, separate auth, separate backup policy, separate monitoring). There is no performance justification for the overhead.

The graph projection layer will be built using Postgres recursive CTEs with indexes on `(from_node, edge_type)` and `(to_node, edge_type)`. Re-evaluate if the portfolio grows beyond 200 services or if unbounded traversal p95 exceeds 50ms in production profiling.

### Edge Case Found and Fixed: Circular Topology in Q6

The `cross_service_boundary` query initially returned a 1-result discrepancy (Postgres 40, Neo4j 39). Investigation identified the cause:

- **Root cause:** The fixture has a circular domain topology (notifications → orders). The original Postgres recursive CTE had no service filter on the recursive step, so traversal looping all the way around the circle counted `orders-svc-0-ep-0` — the probe node itself — as a cross-service result.
- **Neo4j behavior:** `WHERE start.service <> target.service` correctly excluded the probe node since its service matched the start service.
- **Fix:** Added `WITH RECURSIVE start_svc AS (SELECT service FROM graph_nodes WHERE id = ?)` and a final `WHERE n.service <> s.service` to the Postgres Q6 query. Both stores now return 39 — semantically correct.
- **Implication for Phase 1 schema:** Any circular service call topology in real repos (Service A → B → C → A) must be handled correctly by the graph projection layer. The fix pattern — capturing start service in a CTE and filtering the final result set — is implemented in production `GraphProjectionService.crossServiceBoundary`.

**Note on Q2 result count:** The re-verification run shows 80 results for `class_depends_on_class_forward` vs 40 in the first run. This is because the incremental edge update benchmark (Q9) adds a new `DEPENDS_ON` edge on each execution, expanding the reachable class set. In production, incremental edge updates use delete-then-insert per node in a single transaction (`IncrementalEdgeUpdater`).

### Spike Artifacts

> **Note (2026-06-05):** Phase 0 spike **source code and the `graph-spike` Maven profile were removed** after the storage decision. Benchmark results and the Q6 circular-topology fix are preserved in this appendix and in `TestSeer_Phase1_SystemDesign.md` §5.1. Production graph traversal lives in [`GraphProjectionService.java`](../src/main/java/io/testseer/backend/graph/GraphProjectionService.java).

- ~~Benchmark code: `src/main/java/io/testseer/spike/graph/`~~ (removed)
- ~~Raw results: `target/graph-spike-report.json`~~ (removed)
- ~~Discrepancy investigator: `GraphSpikeDiscrepancyInvestigator.java`~~ (removed)
- ~~Build profile: `mvn -Pgraph-spike`~~ (removed)
- Q6 fix pattern is implemented in production `GraphProjectionService.crossServiceBoundary`
