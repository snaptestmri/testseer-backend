# TestSeer Central Backend — Phase 1 User Stories

> **Status:** Canonical  
> **Last verified:** 2026-06-05

**Phase:** Phase 1 (Weeks 3–6): Platform MVP
**Date:** 2026-05-21
**Covers:** Service registry, GitHub webhook ingestion, analysis workers, Postgres fact store, graph projection layer, query API, MCP, PR impact analysis

**Not in scope (Phase 1 as built):** IntelliJ/CLI API-backed test plans, `/v1/gaps`, Pub/Sub plugin push. See [CURRENT_STATUS.md](../../docs/CURRENT_STATUS.md).

---

## Service Registry

**Engineering Lead**
- As an engineering lead, I want to register a repository and its services in the TestSeer registry so that the backend knows what to index and workers can begin ingesting it.
- As an engineering lead, I want to mark a service as `module_type: library` when registering a shared library module so that the backend indexes its types and interfaces but does not expect endpoint or outbound call facts from it.
- As an engineering lead, I want to disable a registered service without deleting its historical facts so that I can pause indexing for sensitive or deprecated repos without losing prior analysis data.
- As an engineering lead, I want to provide manual overrides for source roots and test roots on a non-standard monorepo layout so that the analysis worker can locate Java source files correctly without guessing from conventions.
- As an engineering lead, I want the registry to reject a registration that is missing required fields (org, repo, service name, build tool) with a clear error message so that misconfigured entries never silently produce empty index jobs.

---

## GitHub Webhook Ingestion

**Engineering Lead**
- As an engineering lead, I want TestSeer to automatically trigger an index job when a pull request is updated on a registered repository so that QA engineers always see facts that reflect the current PR state without manually re-triggering.
- As an engineering lead, I want TestSeer to trigger an incremental index job on a default-branch push so that merged changes are reflected in test plans within 5 minutes without a full re-index.
- As an engineering lead, I want the ingestion orchestrator to validate GitHub webhook signatures before processing any payload so that spoofed or malformed webhook events cannot trigger unauthorized index jobs.
- As an engineering lead, I want PR index jobs to execute before nightly batch jobs when both are queued so that active engineering work is never blocked waiting behind scheduled maintenance scans.
- As an engineering lead, I want failed index jobs to be retried automatically with backoff and moved to a dead-letter queue after exhausting retries so that transient failures self-heal and persistent failures are visible for investigation.

**QA Engineer**
- As a QA engineer, I want the backend to index my PR's changes automatically when I push so that I can generate a test plan from the plugin without waiting for a manual trigger or worrying about stale facts.

---

## Analysis Workers

**QA Engineer**
- As a QA engineer, I want analysis workers to extract endpoint mappings (`@GetMapping`, `@PostMapping`, etc.), constructor dependencies, and outbound calls (`RestClient`, `WebClient`, `RestTemplate`, Feign) from my service's Java source so that generated test plans reflect the real code, not scaffolded placeholders.
- As a QA engineer, I want the worker to detect that my service connects to an Oracle database and emit a "verify before using Testcontainers" (Tier 2) prerequisite rather than a direct Testcontainers recommendation so that I'm not handed a fixture strategy that will fail at test runtime.
- As a QA engineer, I want the worker to detect that my service's datasource is externalized via Spring Cloud Config and emit a "manual setup required — declare peripheral in `.testseer/config.yml`" prerequisite so that I know exactly what to do rather than receiving a silent unknown.
- As a QA engineer, I want every extracted fact to carry an `evidence_source` (javaparser), `confidence` score, and `indexed_at` timestamp so that I can see how fresh and trustworthy each piece of the test plan is.

**Engineering Lead**
- As an engineering lead, I want worker output for a Spring Boot controller to be semantically equivalent whether the service was indexed by JavaParser or PSI so that QA engineers get consistent plans regardless of which surface they use.
- As an engineering lead, I want analysis workers to write structured facts to Postgres and raw parsed models to MongoDB atomically so that a partial write failure never leaves the two stores out of sync.
- As an engineering lead, I want the worker to process changed files per service (not per repo) so that a change in one service does not trigger re-indexing of unrelated services in the same monorepo.

---

## Fact Store

**Engineering Lead**
- As an engineering lead, I want all fact records keyed by `org_id/repo/service/commit_sha/symbol_fqn` so that facts from different organisations, repos, and commits are cleanly partitioned and never collide.
- As an engineering lead, I want the fact store to maintain both a full baseline snapshot and incremental deltas per changed-file set so that I can query either the last full index or the latest incremental state without running a full re-index on every change.
- As an engineering lead, I want any breaking change to the fact store schema to require a version bump, a snapshot update, and a CHANGELOG entry so that consumers are never silently broken by schema drift.

**QA Engineer**
- As a QA engineer, I want to query facts for a class and receive both the baseline snapshot and any incremental deltas applied since the last full index so that I always get the most current picture of the code, not a stale baseline.

---

## Graph Projection Layer

**QA Engineer**
- As a QA engineer testing Service A, I want the backend to resolve which services Service A transitively calls — with no depth limit — so that my test plan includes stub specs for the full dependency chain, not just direct calls.
- As a QA engineer, I want to query which services are affected if a shared DTO changes so that I know which other service owners I need to coordinate with before shipping.
- As a QA engineer testing an endpoint in Service A, I want the backend to follow outbound call edges across service boundaries and resolve endpoint definitions in Service B so that my stub specs use real path/method/response shapes rather than guesses.
- As a QA engineer, I want shared library types resolved to their canonical `symbol_fqn` definition with provenance metadata so that fixture synthesis uses the correct field constraints from the authoritative source, not a copy in a downstream service.

**Engineering Lead**
- As an engineering lead, I want graph traversal queries — including full unbounded reachability — to return in under 100ms at 40-service scale so that the query API can serve plugin requests within the 300ms end-to-end SLO.
- As an engineering lead, I want incremental edge updates for a single changed file to complete in under 5ms so that the freshness SLO (PR changes reflected within 5 minutes) is not bottlenecked by write latency.

---

## Query API

**QA Engineer**
- As a QA engineer, I want the query API to return freshness metadata (`indexed_at`, `commit_sha`) with every response so that I can see immediately whether the facts behind my test plan are current or stale.
- As a QA engineer, I want the plugin to fall back to local parsing transparently when the query API is unreachable so that I can keep working in an air-gapped environment or during an outage without any change to the output format.
- As a QA engineer, I want the plugin to show a "stale index" warning when `indexed_at` is older than the configured threshold so that I know to treat the plan with extra scrutiny rather than discovering stale facts at test runtime.

**Engineering Lead**
- As an engineering lead, I want all query API responses to be schema-versioned so that plugin and CLI consumers can ignore unknown optional fields added in future versions without breaking.
- As an engineering lead, I want the query API to return P95 latency under 300ms under normal load so that the 300ms end-to-end plan generation SLO is achievable for API-backed plugin sessions.
- As an engineering lead, I want the query API to scale independently from the analysis workers on GKE so that an ingestion spike does not degrade plan generation latency for QA engineers actively using the plugin.

---

## PR Impact Analysis

**QA Engineer / Developer (via MCP or REST)**
- As a developer with a changed commit, I want to see which symbols changed, which upstream services may break, and which tests to run so that I know my test scope before opening a PR.
- As a developer, I want missing test classes flagged for changed production code so that I can add coverage where gaps exist.

*Shipped:* `GET /v1/impact/pr`, MCP tool `testseer_get_impact`.

---

## MCP / Cursor Agent

**Developer using Cursor**
- As a developer in Cursor, I want the agent to auto-detect my serviceId from git remote or `.testseer/config.yml` so that I do not need to look up UUIDs manually.
- As a developer, I want to ask "what should I test for this PR?" and receive structured impact analysis from the indexed backend.
- As a developer reviewing a PR, I want changed Java files mapped to indexed endpoints even when the PR commit is not yet indexed.

*Shipped:* `testseer-mcp` — detect, impact, changed-endpoints, list/status, trigger-index, service description.  
*Blocked:* `testseer_get_gaps` until `GET /v1/gaps` (P12).

---

## Edge Cases

- As a QA engineer, when I trigger plan generation for a service that has never been indexed, I want a clear "not yet indexed" message with instructions to register the service, rather than an empty plan with no explanation. *(REST/MCP return `NOT_INDEXED`; IntelliJ plugin does not call backend today.)*
- As a QA engineer, when my PR is still being indexed (job in-flight), I want the plugin to show "indexing in progress" and offer the last known good facts rather than blocking or returning nothing. *(REST/MCP return `INDEXING` with envelope; IntelliJ plugin not wired.)*
- As an engineering lead, when a webhook arrives for a repo that is not registered in the service registry, I want the event to be silently dropped with a log entry rather than creating a failed index job for an unconfigured service.
- As an engineering lead, when an analysis worker encounters a Java construct it cannot parse (e.g. a complex annotation processor output), I want it to emit an explicit "unsupported construct" fact with a reason code rather than silently omitting the class from the index.
