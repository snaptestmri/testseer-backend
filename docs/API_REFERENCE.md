# TestSeer API Reference

> **Status:** Canonical (human-readable companion to `openapi.yaml`)  
> **Last verified:** 2026-06-15  
> **Base URL (local):** `http://localhost:8080`  
> **Machine contract:** [openapi.yaml](openapi.yaml) · **Live:** `/swagger-ui/index.html` · `/v3/api-docs.yaml`  
> **Conventions:** [TestSeer_REST_API_Design.md](TestSeer_REST_API_Design.md)  
> **Gap examples (live JSON):** [IMPLEMENTATION_GAPS.md §6](../../docs/IMPLEMENTATION_GAPS.md#6-gap-api-examples-quotient-full)

This document lists every REST endpoint with expected HTTP status codes, response shapes, and common error cases. For field-level schemas, use Swagger or `openapi.yaml`.

---

## 1. How responses are classified

| API class | Paths | Success body | Errors |
|-----------|-------|--------------|--------|
| **Commands** | `/registry/*`, `/admin/*`, `/webhook/*` | Direct DTO (no envelope) | `ApiError` JSON on 4xx/5xx |
| **Queries** | `/v1/facts/*`, `/v1/graph/*`, `/v1/catalog/*`, `/v1/gaps/*`, most `/v1/impact/*` | `ResponseEnvelope<T>` | `ApiError` **or** envelope + freshness HTTP (see §3) |
| **Artifacts** | `/v1/services/{id}/description`, `/v1/jobs/{id}` | Typed DTO | `ApiError` |
| **Portfolio status** | `GET /v1/status` | `OrgStatusSummary` (no envelope) | `ApiError` on bad params |

Every response includes header **`X-Request-Id`** (echoed in `ApiError.requestId` when present).

---

## 2. Shared response models

### 2.1 `ApiError` (commands & hard failures)

```json
{
  "error": "NOT_FOUND",
  "message": "Service '06c8e16d-a24b-4e14-84e7-8714fdfb0a1e' not registered",
  "hint": "Register via POST /registry/services",
  "requestId": "f47ac10b-58cc-4372-a567-0e02b2c3d479"
}
```

| `error` | HTTP | Typical cause |
|---------|------|----------------|
| `VALIDATION_ERROR` | **400** | Missing/invalid query param, bean validation, malformed JSON body |
| `NOT_FOUND` | **404** | Unknown `serviceId`, `jobId`, or registry entry |
| `NOT_INDEXED` | **404** | Rare on commands; queries prefer envelope (§3) |
| `CONFLICT` | **409** | Duplicate service registration; index job already `QUEUED`/`RUNNING` |
| `SERVICE_UNAVAILABLE` | **503** | Dependency down |
| `INTERNAL_ERROR` | **500** | Unexpected server error (generic message; details in server logs) |

Implementation: `io.testseer.backend.api.TestSeerExceptionHandler`.

### 2.2 `ResponseEnvelope<T>` (indexed queries)

```json
{
  "schemaVersion": "1.0",
  "indexedAt": "2026-06-15T14:30:00.000Z",
  "commitSha": "abc123def456",
  "freshnessStatus": "CURRENT",
  "data": { }
}
```

Optional envelope fields (when applicable): `rulePackHash`, `liveConfigEnv`, `liveConfigStatus`.

### 2.3 `PageResult<T>` (catalog paging)

Catalog list endpoints wrap items in a page object inside `data`:

```json
{
  "schemaVersion": "1.0",
  "freshnessStatus": "CURRENT",
  "indexedAt": "2026-06-15T14:30:00.000Z",
  "commitSha": "abc123",
  "data": {
    "items": [ { "entityFqn": "...", "physicalName": "..." } ],
    "total": 572,
    "limit": 50,
    "offset": 0,
    "hasMore": true
  }
}
```

| Query param | Default | Max |
|-------------|---------|-----|
| `limit` | 50 | 200 |
| `offset` | 0 | — |

---

## 3. Freshness HTTP (service-scoped queries)

Endpoints that read **indexed facts for a `serviceId`** map `freshnessStatus` to HTTP:

| `freshnessStatus` | HTTP | `data` |
|-------------------|------|--------|
| `CURRENT` | **200** | Full result |
| `STALE` | **200** | Full result (check `indexedAt`) |
| `INDEXING` | **202** | Empty or last-known (endpoint-specific) |
| `NOT_INDEXED` | **404** | `null`; envelope still present |

**Exceptions (always 200):**

| Endpoint | Notes |
|----------|-------|
| `GET /v1/status/{serviceId}` | Reports freshness; does not fail when not indexed |
| `GET /v1/status` | Per-service freshness in `OrgStatusSummary.services[]` |
| `GET /v1/graph/shared-type` | Global graph query; no single-service freshness gate |
| `GET /v1/graph/type-fanout` | Global graph query; no single-service freshness gate |

Applies to: `/v1/facts/*`, `/v1/graph/*` (except shared-type/type-fanout), `/v1/catalog/*`, `/v1/gaps/*` (service-scoped), `/v1/impact/pr`, Option C messaging routes.

**Client pattern:**

```bash
# 404 + NOT_INDEXED → index first
curl -s -o /dev/null -w "%{http_code}" \
  "http://localhost:8080/v1/facts/class?serviceId=UNKNOWN&symbolFqn=com.example.Foo"
# → 404

# 202 + INDEXING → poll GET /v1/status/{serviceId}
curl -s "http://localhost:8080/v1/status/06c8e16d-a24b-4e14-84e7-8714fdfb0a1e"
```

---

## 4. Endpoint reference

Legend: **Env** = `ResponseEnvelope` · **DTO** = direct JSON · **Fresh** = uses §3 freshness HTTP.

### 4.1 Service registry

| Method | Path | Key inputs | Success | Errors |
|--------|------|------------|---------|--------|
| `GET` | `/registry/services` | — | **200** `ServiceEntry[]` | — |
| `POST` | `/registry/services` | body: `RegistrationRequest` | **201** `ServiceEntry` + `Location` | **400** `ApiError`; **409** duplicate |
| `GET` | `/registry/services/{serviceId}` | path: `serviceId` | **200** `ServiceEntry` | **404** `ApiError` |
| `PATCH` | `/registry/services/{serviceId}` | body: `RegistryUpdateRequest` | **200** `ServiceEntry` | **404** `ApiError` |
| `DELETE` | `/registry/services/{serviceId}` | path: `serviceId` | **204** empty | **404** `ApiError` |

**Register example:**

```bash
curl -s -X POST http://localhost:8080/registry/services \
  -H 'Content-Type: application/json' \
  -d '{"orgId":"quotient","repo":"optimus-offer-services-suite","serviceName":"optimus-offer-services-suite","buildTool":"MAVEN"}'
```

### 4.2 Admin — discovery & indexing

| Method | Path | Key inputs | Success | Errors |
|--------|------|------------|---------|--------|
| `POST` | `/admin/discover` | `orgId` (query) | **200** `DiscoveryResult` | **400** missing `orgId` |
| `POST` | `/admin/index/{serviceId}` | optional body: `IndexTriggerRequest` | **202** `IndexTriggerResponse` (`jobId`) | **404** service; **409** job in flight |
| `POST` | `/admin/index/local` | body: `LocalIndexTriggerRequest` (`path`, `orgId`, …) | **200** `LocalIndexTriggerResponse` | **400** bad path / validation |
| `POST` | `/admin/index/clear` | body: `IndexClearRequest` (`scope`, `serviceId`, `orgId`) | **200** `IndexClearResponse` | **400** `ApiError` |
| `DELETE` | `/admin/index/{serviceId}` | path: `serviceId` | **200** `IndexClearResponse` | **404** `ApiError` |
| `POST` | `/admin/maven/backfill-links` | body: `MavenLinkBackfillRequest` (`orgId`, optional `serviceId`, `syncOwnedByEdges`) | **200** `MavenLinkBackfillResponse` | **400** missing org/service |
| `POST` | `/admin/jobs/{jobId}/replay` | path: `jobId` | **202** `IndexTriggerResponse` | **404** job; **409** not DLQ |

**Clear scopes:** `SERVICE` (default), `MESSAGING`, `ORG` (optional `includeRegistry=true`).

**Trigger index example:**

```bash
curl -s -X POST "http://localhost:8080/admin/index/06c8e16d-a24b-4e14-84e7-8714fdfb0a1e" \
  -H 'Content-Type: application/json' -d '{}'
# → 202 {"jobId":"...","status":"QUEUED",...}
```

### 4.3 Query — status & jobs

| Method | Path | Key inputs | Success | Fresh | Errors |
|--------|------|------------|---------|-------|--------|
| `GET` | `/v1/status` | `orgId` (query) | **200** `OrgStatusSummary` | N/A (always 200) | **400** missing `orgId` |
| `GET` | `/v1/status/{serviceId}` | path: `serviceId` | **200** `ResponseEnvelope` status DTO | Always **200** | **404** unknown service (`ApiError`) |
| `GET` | `/v1/jobs/{jobId}` | path: `jobId` | **200** job status DTO | N/A | **404** `ApiError` |

### 4.4 Query — facts

| Method | Path | Required params | Success body (`data`) | Fresh |
|--------|------|-----------------|----------------------|-------|
| `GET` | `/v1/facts/class` | `serviceId`, `symbolFqn` | `ClassFactView` | Yes |
| `GET` | `/v1/facts/by-file` | `serviceId`, `filePath` (repeatable) | `SymbolFactView[]` | Yes |
| `GET` | `/v1/facts/outbound` | `serviceId` | `OutboundCallView[]` | Yes |
| `GET` | `/v1/facts/data-access` | `serviceId`, filters | accessor/catalog facts | Yes |
| `GET` | `/v1/facts/entry-triggers` | `serviceId` | entry trigger facts | Yes |
| `GET` | `/v1/facts/external-endpoints` | `serviceId` | external HTTP endpoints | Yes |
| `GET` | `/v1/facts/gates` | `serviceId`, `env` | live-flow gate facts | Yes |
| `GET` | `/v1/facts/message-schema` | `serviceId`, `topicShortId` | schema binding facts (`payloadProto`, `payloadFields`, `unpackExpression`) | Yes |
| `GET` | `/v1/facts/pubsub` | `serviceId`, `env`, optional `liveVerify` | pub/sub inventory; each row includes `transport` (`PUBSUB` \| `KAFKA`) | Yes |
| `GET` | `/v1/facts/pubsub/org` | `orgId`, `env`, optional filters | org-wide inventory (topic dropdown in viz); `transport` per row | Org-level |
| `GET` | `/v1/facts/validation-hints` | `serviceId`, `env` | `ValidationHintView[]` | Yes |
| `GET` | `/v1/facts/contract-operations` | `serviceId`, optional `specDomain` | `ContractOperationView[]` | Yes |
| `GET` | `/v1/facts/contract-operations/linked` | `serviceId`, optional `specDomain` | `ContractOperationLinkedView[]` | Yes |

**Example (contract operations — BL-046):**

```bash
curl -s "http://localhost:8080/v1/facts/contract-operations?serviceId=<catalog-service-id>&specDomain=Offers"
```

Query by catalog library `serviceId` (`optimus-platform-apis`) or an implementing service mapped via `quotient-api-contracts.yml`. Optional `specDomain` filters to one OpenAPI domain (e.g. `Offers`, `Rebate`).

**Example (class facts):**

```bash
curl -s "http://localhost:8080/v1/facts/class?serviceId=06c8e16d-a24b-4e14-84e7-8714fdfb0a1e&symbolFqn=com.quotient.platform.offeringestion.service.UserTargetingService"
```

All return **Env**; **400** if required params missing.

### 4.5 Query — graph

| Method | Path | Required params | `data` type | Fresh |
|--------|------|-----------------|-------------|-------|
| `GET` | `/v1/graph/neighborhood` | `serviceId`, `symbolFqn` or `nodeId` | neighborhood graph | Yes |
| `GET` | `/v1/graph/reachability` | `serviceId`, optional `type` (`service`\|`class`\|`method`), `symbolFqn`, `methodName`, `nodeId`, `depth` | reachable node ids | Yes |
| `GET` | `/v1/graph/routing` | `serviceId`, optional `factoryFqn` | factory → processor routing table (BL-053) | Yes |
| `GET` | `/v1/graph/impact` | `serviceId`, `nodeId` | downstream impact | Yes |
| `GET` | `/v1/graph/entry-flow` | `serviceId`, `triggerId` or `path`, optional TRG-12 flags | entry-flow trace (+ optional `messagingFlow`, `crossRepoFlow`) | Yes |
| `GET` | `/v1/graph/entry-flow/impact` | `orgId`, `handlerFqn`, optional `serviceId`, `env` | reverse trigger impact (TRG-13) | Org-level |
| `GET` | `/v1/graph/contract-entry-flow` | `serviceId`, `operationId` or path+method | contract → handler entry-flow | Yes |
| `GET` | `/v1/graph/event-flow` | `serviceId`, `topicShortId`, `env` | in-repo event flow | Yes |
| `GET` | `/v1/graph/event-flow/cross-repo` | `orgId`, `topicShortId`, `env` | cross-repo trace + `gaps[]`; `hops[].transport` | Partial (org-level) |
| `GET` | `/v1/graph/cross-service-boundary` | `serviceId`, boundary params | boundary edges | Yes |
| `GET` | `/v1/graph/shared-type` | `typeFqn` | consumers of shared type | **No** (global) |
| `GET` | `/v1/graph/type-fanout` | `typeFqn` | type fan-out | **No** (global) |

Cross-repo trace gap types on `data.gaps[]`: `NO_PUBLISHER`, `NO_SUBSCRIBER`. See [IMPLEMENTATION_GAPS.md §6](../../docs/IMPLEMENTATION_GAPS.md).

**Reachability (BL-053):** For `type=class` or `type=method`, pass `symbolFqn` (and `methodName` for methods) or full `nodeId` (`{serviceId}::class::{fqn}` / `{serviceId}::method::{fqn#method}`). Bare `serviceId` with `type=class` returns **400**.

```bash
curl -s "http://localhost:8080/v1/graph/reachability?serviceId=0bab295f-...&type=class&symbolFqn=com.quotient.platform.transaction.eval.service.TransactionEvaluationService"
curl -s "http://localhost:8080/v1/graph/routing?serviceId=0bab295f-...&factoryFqn=com.quotient.platform.transaction.eval.processors.ProcessorFactory"
```

**Entry-flow `processorRouting[]` (BL-053):** additive on `GET /v1/graph/entry-flow` — `PROCESSOR_ROUTING` steps with `factoryFqn`, `possibleProcessors[]`.

**Cross-repo hop shape (additive fields):**

```json
{
  "order": 1,
  "topicShortId": "QUOT.SALES.TRANSACTION.PIPELINE.EVENTS",
  "transport": "KAFKA",
  "publishers": [],
  "subscribers": [{ "serviceId": "...", "transport": "KAFKA", "linkedClassFqn": "..." }]
}
```

`transport` is one of `PUBSUB` (default), `KAFKA`, or `HTTP_PUBSUB` when `pubsub_resource_facts.attributes` includes `"transport":"KAFKA"` or `"transport":"HTTP_PUBSUB"`. Parsed by `MessagingTransportUtil` at query time (BL-050 / BL-051 / BL-048 P4).

**Event-flow step shape (additive — BL-051):**

```json
{
  "handler": "com.quotient.platform.transaction.eval.processors.ReceiptTxnEvalProcessor",
  "outbounds": [
    {
      "topicOrType": "DEV_T.NOTIFICATION_REQ",
      "role": "PUBLISH",
      "payloadOrResource": "http://…/pubsub/service/publish",
      "transport": "HTTP_PUBSUB"
    },
    {
      "topicOrType": "QUOT.REBATE.REWARD-STATUS.EVENTS",
      "role": "PUBLISH",
      "payloadOrResource": null,
      "transport": "KAFKA"
    }
  ],
  "outbound": { "topicOrType": "DEV_T.NOTIFICATION_REQ", "role": "PUBLISH", "…": "…" }
}
```

`outbound` remains the first element of `outbounds[]` for backward compatibility. HTTP publish detail also appears in `externalEndpoints[]` when `includeExternal=true`.

**Event Flow viz detail panel** (no new routes): participant click in `/viz.html` calls `GET /v1/facts/message-schema?serviceId&topicShortId` and `GET /v1/facts/gates?serviceId&env`. Message-schema rows render proto type, direction, unpack, and a **`payloadFields`** table (#, field name, type; `repeated` when applicable) — see [22-event-flow-viz-redesign.md](features/22-event-flow-viz-redesign.md) §Phase 4 / P4.1.

**Entry-flow chain (TRG-12)** — optional on `/v1/graph/entry-flow`:

```bash
curl -s "http://localhost:8080/v1/graph/entry-flow?serviceId=SUB_SVC&triggerId=pubsub:pdn_s.riq_offer_event:...&env=pdn&includeMessaging=true&crossRepo=true&orgId=quotient"
```

Returns `steps[]` plus `messagingTopicShortId`, `messagingFlow`, `crossRepoFlow` when flags are set. See [21-trg-12-entry-flow-chain.md](features/21-trg-12-entry-flow-chain.md).

### 4.6 Query — catalog

| Method | Path | Required params | `data` type | Fresh |
|--------|------|-----------------|-------------|-------|
| `GET` | `/v1/catalog/data-objects` | `serviceId` | `PageResult<DataObjectView>` | Yes |
| `GET` | `/v1/catalog/schema-objects` | `serviceId` | `PageResult<SchemaObjectView>` | Yes |

Optional filters: `storeType`, `physicalName`, `limit`, `offset`.

### 4.7 Query — gaps & consistency

| Method | Path | Required params | `data` type | Fresh |
|--------|------|-----------------|-------------|-------|
| `GET` | `/v1/gaps` | `serviceId` | `GapReport` (test class gaps) | Yes |
| `GET` | `/v1/gaps/data-objects` | `orgId` | `DataObjectGapView[]` | Org/library scoped |
| `GET` | `/v1/gaps/consistency` | `orgId`, `serviceId` | `ConsistencyGapView[]` | Yes |
| `GET` | `/v1/gaps/messaging` | `serviceId`, `env` | `MessagingGapView[]` | Yes |
| `GET` | `/v1/gaps/contract` | `serviceId`, optional `specDomain` | `ContractGapView[]` | Yes |
| `GET` | `/v1/consistency/scenarios` | `serviceId` | static scenario hints | Yes |

**Gap type enums:**

| Surface | `gapType` / `kind` values |
|---------|---------------------------|
| Data objects | `LIBRARY_NOT_INDEXED`, `LIBRARY_STALE`, `HANDLER_WITHOUT_CATALOG`, `INFERRED_NOT_IN_DDL`, `DDL_UNREFERENCED` |
| Consistency | `UNDOCUMENTED_DUAL_WRITE`, `ORPHAN_RULE_PACK`, `UNLINKED_MIRROR` |
| Messaging | `MISSING_SCHEMA`, `UNGUARDED_STEP`, `UNLINKED_PUBSUB` |
| Contract (BL-046) | `CONTRACT_ONLY`, `IMPLEMENTATION_ONLY` |
| Portfolio (`/v1/gaps`) | row `kind`: `ENDPOINT_CONTROLLER`, `CLASS` |

### 4.8 Analysis — impact

| Method | Path | Required params | Success | Fresh |
|--------|------|-----------------|---------|-------|
| `GET` | `/v1/impact/pr` | `serviceId`, `commitSha` | **200** Env `ImpactReport` | Yes |

`ImpactReport` fields: `changedSymbols`, `affectedConsumers`, `downstreamDependencies`, `suggestedTestScope`, `missingTestClasses`.

### 4.9 Artifacts — service description

| Method | Path | Success | Errors |
|--------|------|---------|--------|
| `GET` | `/v1/services/{serviceId}/description` | **200** `ServiceDescriptionResponse` JSON | **404** when no description stored |

```json
{
  "serviceId": "06c8e16d-a24b-4e14-84e7-8714fdfb0a1e",
  "description": "Handles offer lifecycle events…",
  "generatedAt": "2026-06-15T09:00:00Z",
  "model": null
}
```

### 4.10 Admin — workspace catalog (CFG-CAT)

| Method | Path | Success | Errors |
|--------|------|---------|--------|
| `GET` | `/v1/workspace` | **200** workspace summary | **400** |
| `POST` | `/v1/workspace/import-from-yaml` | **200** import result | **400** validation |
| `GET` | `/v1/workspace/catalog-libraries` | **200** library list | — |
| `POST` | `/v1/workspace/catalog-libraries` | **201** created | **400**, **409** |
| `GET/PATCH/DELETE` | `/v1/workspace/catalog-libraries/{libraryId}` | CRUD | **404** |
| `POST` | `/v1/workspace/catalog-libraries/{libraryId}/register` | **201** registers index target | **404** |
| `GET/POST/PATCH/DELETE` | `/v1/workspace/service-modules` … | module CRUD | **404**, **409** |

Feature doc: [features/16-workspace-catalog-config.md](features/16-workspace-catalog-config.md).

### 4.11 Notifications (IDE cache push)

| Method | Path | Success | Notes |
|--------|------|---------|-------|
| `GET` | `/v1/notifications/index-events` | **200** SSE stream | Long-lived; index-complete events |
| `GET` | `/v1/notifications/index-events/poll` | **200** event batch | Poll alternative to SSE |

### 4.12 Webhook

| Method | Path | Success | Errors |
|--------|------|---------|--------|
| `POST` | `/webhook/github` | **202** queued; **200** ignored event | **401** bad HMAC; **500** internal |

GitHub delivery contract; no `ApiError` on **401** (plain rejection).

---

## 5. Common workflows

### 5.1 Register → index → query

```bash
ORG=quotient
BASE=http://localhost:8080

# 1. Register (or use scripts/register-all-repos.sh)
SID=$(curl -s -X POST "$BASE/registry/services" -H 'Content-Type: application/json' \
  -d "{\"orgId\":\"$ORG\",\"repo\":\"my-service\",\"serviceName\":\"my-service\",\"buildTool\":\"MAVEN\"}" \
  | jq -r .serviceId)

# 2. Index (async)
curl -s -X POST "$BASE/admin/index/$SID" -H 'Content-Type: application/json' -d '{}'

# 3. Poll status until CURRENT
curl -s "$BASE/v1/status/$SID" | jq '.freshnessStatus, .data.lastJobStatus'

# 4. Query facts
curl -s "$BASE/v1/facts/outbound?serviceId=$SID" | jq '.freshnessStatus, (.data | length)'
```

### 5.2 Offer-event bundle (quotient)

```bash
./scripts/clear-index.sh ORG quotient
./scripts/index-all-repos.sh quotient http://localhost:8080
```

Then use service IDs and curl examples in [IMPLEMENTATION_GAPS.md §6](../../docs/IMPLEMENTATION_GAPS.md#6-gap-api-examples-quotient-full).

### 5.3 Catalog browse with paging

```bash
curl -s "http://localhost:8080/v1/catalog/data-objects?serviceId=abaca039-3f28-4e00-8785-dc9b7c7e93fe&limit=10&offset=0" \
  | jq '{freshness: .freshnessStatus, total: .data.total, hasMore: .data.hasMore, count: (.data.items | length)}'
```

---

## 6. OpenAPI maintenance

After changing any controller route or DTO:

```bash
cd testseer-backend
./mvn21 test -Dtest=OpenApiExportTest
# commit docs/openapi.yaml + CHANGELOG.md
../scripts/openapi-governance-check.sh
```

If this reference and `openapi.yaml` disagree, **`openapi.yaml` wins** (CI-governed).

---

## 7. Related documentation

| Document | Use when |
|----------|----------|
| [openapi.yaml](openapi.yaml) | Field-level schemas, try-it in Swagger |
| [TestSeer_REST_API_Design.md](TestSeer_REST_API_Design.md) | ADRs, versioning header (R4 planned), exception architecture |
| [features/README.md](features/README.md) | Per-feature behavior and data sources |
| [Option_C_Messaging_Flow.md](Option_C_Messaging_Flow.md) | Messaging curl cookbook |
| [IMPLEMENTATION_GAPS.md §6](../../docs/IMPLEMENTATION_GAPS.md) | Observed vs expected gap API payloads |
| [REQUIREMENTS.md §11](../../docs/REQUIREMENTS.md) | Requirements-level API inventory |

---

## 8. Document history

| Date | Change |
|------|--------|
| 2026-06-15 | Initial consolidated reference (endpoints, errors, freshness, paging, gap enums) |
