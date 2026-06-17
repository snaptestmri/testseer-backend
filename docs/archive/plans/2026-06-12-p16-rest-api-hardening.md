# P16: REST API Hardening — Implementation Plan

> **For agentic workers:** Implement phase-by-phase (R1 → R4). Do not skip R1 — it unblocks CI governance. Check boxes (`- [ ]`) as tasks complete.

**Goal:** Align TestSeer REST API with [TestSeer_REST_API_Design.md](../../TestSeer_REST_API_Design.md): fix OpenAPI drift, unify error responses, align messaging freshness HTTP, and add header-based API versioning.

**Architecture:** Add `io.testseer.backend.api` package with `ApiError` + global `TestSeerExceptionHandler`. Refactor controllers to remove inline handlers. Regenerate `openapi.yaml` from springdoc. Update `testseer-mcp` client for JSON errors and description shape.

**Tech Stack:** Spring Boot 3.3, springdoc-openapi, MockMvc, Testcontainers, TypeScript MCP client

**Prerequisites:** Backend MVP shipped (P1–P11, Option C, observability O1–O4). Read design doc §3 baseline before starting.

**Design reference:** [TestSeer_REST_API_Design.md](../../TestSeer_REST_API_Design.md)

---

## Implementation status (2026-06-12)

| Phase | Status | Shipped artifacts |
|-------|--------|---------------------|
| **R1** | ✅ Complete | `OpenApiGovernanceTest`, `scripts/openapi-governance-check.sh`, regenerated `openapi.yaml` |
| **R2** | ✅ Complete | `ApiError`, `ApiErrorCode`, `TestSeerExceptionHandler`, `RequestIdHolder`; removed `RegistryExceptionHandler` |
| **R3** | ✅ Complete | `FreshnessHttp`, `ServiceDescriptionResponse` JSON, MCP `client.ts` updates |
| **R4** | ⏳ Planned | `ApiVersionFilter`, MCP `X-TestSeer-Api-Version` header |

Task checkboxes below remain as the original implementation checklist; treat R1–R3 steps as **done** unless a box is explicitly still open for R4.

---

## Phase overview

| Phase | Name | Effort | Breaking | Ship independently? |
|-------|------|--------|----------|-------------------|
| **R1** | OpenAPI sync + CI gate | 0.5–1 day | No | ✅ Yes |
| **R2** | Unified `ApiError` + global handler | 1–2 days | Soft* | ✅ Yes |
| **R3** | Freshness parity + description JSON | 1 day | Soft** | ✅ Yes |
| **R4** | API version header | 0.5 day | No | ✅ Yes |

\* Clients parsing plain-string errors must switch to JSON (document in CHANGELOG).  
\** Description endpoint response shape changes from plain text to JSON.

---

## File structure (new / modified)

```
src/main/java/io/testseer/backend/
├── api/
│   ├── ApiError.java
│   ├── ApiErrorCode.java
│   ├── RequestIdHolder.java
│   ├── ApiVersionFilter.java
│   └── TestSeerExceptionHandler.java
├── registry/
│   └── ServiceRegistryController.java      (remove reliance on RegistryExceptionHandler)
├── admin/
│   ├── IndexTriggerController.java         (remove inline handlers)
│   ├── IndexClearController.java
│   └── LocalIndexTriggerController.java
├── analysis/
│   └── ServiceDescriptionController.java   (JSON responses)
└── query/
    └── MessagingQueryController.java         (freshness HTTP)

src/test/java/io/testseer/backend/
├── api/
│   └── TestSeerExceptionHandlerTest.java
├── OpenApiExportTest.java                  (existing)
└── openapi/
    └── OpenApiGovernanceTest.java          (new — diff gate)

scripts/
└── openapi-governance-check.sh               (new)

docs/
├── openapi.yaml                              (regenerated)
└── TestSeer_REST_API_Design.md               (design — this plan's parent)

testseer-mcp/src/
└── client.ts                                 (error + description parsing)
```

---

## Phase R1 — OpenAPI sync + CI gate

**Goal:** Close G-01. Committed `openapi.yaml` matches live springdoc output.

### Task R1-1: Regenerate OpenAPI

**Files:**
- Modify: `docs/openapi.yaml` (generated)
- Verify: `OrgStatusController`, `JobStatusController` annotated for springdoc

- [ ] **Step 1:** Ensure `OrgStatusController` and `JobStatusController` have `@Tag`, `@Operation`, `@ApiResponse` annotations (match other query controllers).

- [ ] **Step 2:** Run export:

```bash
cd testseer-backend
mvn test -Dtest=OpenApiExportTest -q
```

- [ ] **Step 3:** Verify new paths present:
  - `GET /v1/status` (org summary)
  - `GET /v1/jobs/{jobId}`

- [ ] **Step 4:** Manually verify `GET /v1/impact/pr` 200 response references `ResponseEnvelopeImpactReport` (fix `@Schema` on controller if springdoc still emits bare `ImpactReport`).

- [ ] **Step 5:** Update [features/03-fact-query-api.md](../../features/03-fact-query-api.md) endpoint table if needed.

### Task R1-2: OpenAPI governance script

**Files:**
- Create: `scripts/openapi-governance-check.sh`
- Create: `src/test/java/io/testseer/backend/openapi/OpenApiGovernanceTest.java`

- [ ] **Step 1:** Create shell script:

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../testseer-backend"
mvn test -Dtest=OpenApiExportTest -q
if ! git diff --quiet docs/openapi.yaml; then
  echo "ERROR: docs/openapi.yaml is out of date. Run: mvn test -Dtest=OpenApiExportTest"
  git diff docs/openapi.yaml
  exit 1
fi
echo "OpenAPI spec is in sync."
```

- [ ] **Step 2:** Add JUnit test that exports to temp file and asserts byte equality with committed `docs/openapi.yaml` (or normalized YAML compare).

- [ ] **Step 3:** Document in [testseer-backend/README.md](../../../README.md) and [docs/README.md](../../../../docs/README.md) governance section.

- [ ] **Step 4:** Add CHANGELOG entry under `### Changed` — "OpenAPI spec synced; added `/v1/status`, `/v1/jobs/{jobId}`."

### R1 exit criteria

- [ ] `openapi.yaml` contains all 32+ live routes
- [ ] Governance script passes on clean tree
- [ ] `mvn test` green

---

## Phase R2 — Unified ApiError + global handler

**Goal:** Close G-02, G-03. Single error shape for all non-envelope failures.

### Task R2-1: ApiError model

**Files:**
- Create: `src/main/java/io/testseer/backend/api/ApiErrorCode.java`
- Create: `src/main/java/io/testseer/backend/api/ApiError.java`
- Create: `src/main/java/io/testseer/backend/api/RequestIdHolder.java`

- [ ] **Step 1:** Implement `ApiErrorCode` enum per design doc §8.3.

- [ ] **Step 2:** Implement `ApiError` record with factory methods:

```java
public static ApiError of(ApiErrorCode code, String message) { ... }
public static ApiError of(ApiErrorCode code, String message, String hint) { ... }
// requestId populated from RequestIdHolder.current()
```

- [ ] **Step 3:** `RequestIdHolder` reads `MdcKeys.REQUEST_ID` from SLF4J MDC.

### Task R2-2: TestSeerExceptionHandler

**Files:**
- Create: `src/main/java/io/testseer/backend/api/TestSeerExceptionHandler.java`
- Create: `src/test/java/io/testseer/backend/api/TestSeerExceptionHandlerTest.java`
- Delete (after migration): `registry/RegistryExceptionHandler.java`

- [ ] **Step 1:** Write MockMvc tests for each exception mapping (404, 409, 400 validation, 500 catch-all).

- [ ] **Step 2:** Implement `@RestControllerAdvice` with handlers listed in design doc §9.2.

- [ ] **Step 3:** Ensure 500 responses never include exception class name or stack trace in JSON body.

- [ ] **Step 4:** Log at WARN for 4xx, ERROR for 5xx with `requestId` and `error` enum.

### Task R2-3: Migrate controllers

**Files:**
- Modify: `IndexTriggerController.java` — remove `@ExceptionHandler` methods
- Modify: `IndexClearController.java` — remove inline handler
- Modify: `LocalIndexTriggerController.java` — remove inline handler
- Modify: `ServiceRegistryController.java` — verify exceptions propagate to global handler
- Delete: `RegistryExceptionHandler.java`

- [ ] **Step 1:** Update existing controller tests to assert JSON:

```java
.andExpect(jsonPath("$.error").value("CONFLICT"))
.andExpect(jsonPath("$.requestId").exists())
```

- [ ] **Step 2:** Fix `IndexTriggerController` 409 — was plain string, now `ApiError`.

- [ ] **Step 3:** Fix empty 404 on index trigger — now `ApiError` with hint.

- [ ] **Step 4:** Run full `mvn test`.

### Task R2-4: OpenAPI error schemas

- [ ] **Step 1:** Add `@ApiResponse` with `ApiError` schema on controller methods (or global springdoc customizer).

- [ ] **Step 2:** Regenerate `openapi.yaml`.

- [ ] **Step 3:** CHANGELOG — "Error responses standardized to ApiError JSON."

### R2 exit criteria

- [ ] No `@ExceptionHandler` on individual controllers (except webhook if special-cased)
- [ ] `RegistryExceptionHandler` removed
- [ ] All admin/registry 4xx tests assert `error` field
- [ ] SC-03 partial — command endpoints done

---

## Phase R3 — Freshness parity + description JSON

**Goal:** Close G-04, G-06.

### Task R3-1: MessagingQueryController freshness HTTP

**Files:**
- Modify: `MessagingQueryController.java`
- Modify: `MessagingQueryControllerTest.java`

- [ ] **Step 1:** Copy freshness pattern from `FactQueryController`:

```java
FreshnessStatus status = freshnessResolver.resolve(serviceId, staleThresholdMinutes);
if (status == FreshnessStatus.NOT_INDEXED) {
    return ResponseEntity.status(404).body(ResponseEnvelope.notIndexed());
}
int httpStatus = status == FreshnessStatus.INDEXING ? 202 : 200;
return ResponseEntity.status(httpStatus).body(envelope);
```

- [ ] **Step 2:** Apply to all methods that take `serviceId`: pubsub, message-schema, data-access, gates, validation-hints, event-flow, gaps/messaging.

- [ ] **Step 3:** `traceCrossRepoFlow` — document behavior: org-wide query may use `FreshnessStatus.CURRENT` when any service in org is indexed, or skip per-service check (align with design doc §6 footnote).

- [ ] **Step 4:** Integration tests: NOT_INDEXED → 404, INDEXING → 202.

- [ ] **Step 5:** Update [features/07-option-c-messaging-flow.md](../../features/07-option-c-messaging-flow.md) REST section.

### Task R3-2: ServiceDescriptionController JSON

**Files:**
- Create: `ServiceDescriptionResponse.java` record
- Modify: `ServiceDescriptionController.java`
- Modify: `ServiceDescriptionControllerTest.java`
- Modify: `testseer-mcp/src/client.ts`

- [ ] **Step 1:** Define response record:

```java
public record ServiceDescriptionResponse(
    String serviceId,
    String description,
    Instant generatedAt,
    String model
) {}
```

- [ ] **Step 2:** GET returns 200 with JSON; 404/503 return `ApiError`.

- [ ] **Step 3:** POST generate returns 200 with JSON (include `generatedAt`, `model`).

- [ ] **Step 4:** Update MCP `getServiceDescription` to parse `.description` field.

- [ ] **Step 5:** Regenerate OpenAPI; update [features/09-service-description.md](../../features/09-service-description.md).

### R3 exit criteria

- [ ] Messaging endpoints pass freshness HTTP tests
- [ ] Description endpoints return JSON only
- [ ] MCP README notes new description shape
- [ ] SC-04, SC-05, SC-06 satisfied

---

## Phase R4 — API version header (optional)

**Goal:** Close G-05 without URL prefix migration. Contract version in header; paths stay stable.

### Task R4-1: ApiVersionFilter

**Files:**
- Create: `src/main/java/io/testseer/backend/api/ApiVersionFilter.java`
- Create: `src/test/java/io/testseer/backend/api/ApiVersionFilterTest.java`
- Modify: `src/main/resources/application.yml` — `testseer.api.supported-versions: [1]`

- [ ] **Step 1:** Filter reads `X-TestSeer-Api-Version`; default `1` if absent.

- [ ] **Step 2:** Echo negotiated version on every response header.

- [ ] **Step 3:** Unknown version → `400 ApiError` via global handler (or filter short-circuit).

- [ ] **Step 4:** Skip validation for `/webhook/*` and `/actuator/*` (implicit v1).

- [ ] **Step 5:** Set MDC key `apiVersion` for logs.

### Task R4-2: MCP + OpenAPI

**Files:**
- Modify: `testseer-mcp/src/client.ts`
- Regenerate: `docs/openapi.yaml`

- [ ] **Step 1:** MCP client sends `X-TestSeer-Api-Version: 1` on every `fetch`.

- [ ] **Step 2:** Add OpenAPI global parameter:

```yaml
components:
  parameters:
    ApiVersionHeader:
      name: X-TestSeer-Api-Version
      in: header
      required: false
      schema: { type: integer, default: 1, enum: [1] }
```

- [ ] **Step 3:** Document in [TestSeer_REST_API_Design.md](../../TestSeer_REST_API_Design.md) §5.3 and MCP README.

- [ ] **Step 4:** CHANGELOG — "API contract version via X-TestSeer-Api-Version header (default 1)."

### R4 exit criteria

- [ ] Omitting header behaves as v1 (backward compatible)
- [ ] Invalid version returns 400 ApiError
- [ ] MCP sends header explicitly
- [ ] Response echoes header on all REST endpoints

---

## Test matrix (minimum)

| Area | Test class | Cases |
|------|------------|-------|
| Global errors | `TestSeerExceptionHandlerTest` | 400, 404, 409, 500 shapes |
| Registry | `ServiceRegistryControllerTest` | 201 Location, 409 ApiError |
| Index trigger | `IndexTriggerControllerTest` | 202, 404 ApiError, 409 ApiError |
| Messaging | `MessagingQueryControllerTest` | 404 NOT_INDEXED, 202 INDEXING |
| Description | `ServiceDescriptionControllerTest` | JSON 200, ApiError 503 |
| OpenAPI | `OpenApiGovernanceTest` | No drift |
| MCP | `client.test.ts` (if exists) | Parse ApiError, description JSON |

---

## Rollout & communication

1. **R1** — merge anytime; no client changes.
2. **R2** — announce in CHANGELOG; MCP update in same PR or immediately after.
3. **R3** — coordinate MCP release with description JSON change.
4. **R4** — optional; non-breaking; ship with or after R2.

### Rollback

Each phase is independently revertable. R2/R3 may require MCP pin to previous commit if rolled back after release.

---

## Dependencies and risks

| Risk | Mitigation |
|------|------------|
| MCP breaks on plain-text errors | Ship MCP + backend R2 together |
| springdoc export non-deterministic ordering | Normalize YAML sort in governance test or compare parsed JSON tree |
| Cross-repo `traceCrossRepoFlow` freshness ambiguous | Document explicit rule in §6; add test with partial org index |
| Clients forget version header on v2+ | Default omitted header to v1 indefinitely; document bump policy |

---

## Document history

| Date | Change |
|------|--------|
| 2026-06-12 | Initial plan from REST API design audit |
