# Receipt Service Graph Gap Issues (RS-GAP-01–07)

> **Status:** Open — Phase 5 design ready  
> **Implementation design:** [TestSeer_RS_Phase5_Implementation_Design.md](../TestSeer_RS_Phase5_Implementation_Design.md)  
> **Backlog:** **BL-061** (proposed)  
> **Pilot:** `platform-receipt-service` · serviceId `df14f277-8b47-4687-a0fd-6a3f5a4b64a6`  
> **Manual graph:** [ReceiptService_ServiceGraph_Manual.md](../../../../Downloads/DesignDocuments/Docs/ReceiptService_ServiceGraph_Manual.md)  
> **TestSeer snapshot:** [ReceiptService_ServiceGraph_TestSeer.md](../../../../Downloads/DesignDocuments/Docs/ReceiptService_ServiceGraph_TestSeer.md)  
> **Last updated:** 2026-06-16

Issue registry for the **receipt-service** `service-graph-pilot`. Mirrors [28-transaction-eval-graph-gap-issues.md](28-transaction-eval-graph-gap-issues.md) structure.

---

## Summary matrix

| ID | Priority | Status | Req | Symptom | Fix step |
|----|----------|--------|-----|---------|----------|
| RS-GAP-01 | P0 | Open | TRG-15 | REST linked to **API interface**, not `@RestController` | Step 1 |
| RS-GAP-01b | P0 | Open | TRG-16 | Duplicate `POST /receipt/submit` collapses to one handler | Step 2 |
| RS-GAP-01c | P1 | Open | TRG-17 | `@*Mapping(params=…)` not in trigger identity | Step 3 |
| RS-GAP-02 | P0 | Open | GRP-19 | Reachability 1 node from controller/service | Step 4 |
| RS-GAP-03 | P1 | Open | OUT-08 | Outbound producers on `receipt-common`, not caller | Step 5 |
| RS-GAP-04 | P2 | Open | GATE-09 | 0 gates on `receiptservice` packagePrefix | Step 6 |
| RS-GAP-05 | — | Scope | — | Suite Kafka/cron triggers vs K8s workload | Filter / doc |
| RS-GAP-06 | — | Expected | — | Flow-diagram downstream eval topic gaps | No fix |
| RS-GAP-07 | P2 | Open | TRG-13-R2 | Reverse impact empty for impl handler FQN | Step 7 |

---

## RS-GAP-01 — REST handler links to interface, not RestController

**Priority:** P0  
**Status:** Open  
**Requirement:** TRG-15

### Symptom

`GET /v1/facts/entry-triggers` returns `linkedHandlerFqn` like:

```
com.quotient.platform.receiptservice.web.api.CAReceiptSubmitServiceAPI
```

not:

```
com.quotient.platform.receiptservice.web.api.CAReceiptSubmitServiceApiController
```

Agents searching for `ReceiptSubmitServiceApiController` find no REST trigger.

### Root cause

`InboundRestTriggerExtractor` sets `linkedHandlerFqn` to the **declaring class** of the mapping annotation. Optimus services declare endpoints on `*ServiceApi` interfaces; `@RestController` classes implement them with `@Override` only.

### Fix

`RestControllerImplementationLinker` — see Phase 5 Step 1.

### Validation

```bash
curl -s "http://localhost:8080/v1/facts/entry-triggers?orgId=quotient&serviceId=df14f277-8b47-4687-a0fd-6a3f5a4b64a6" \
  | jq '[.data[] | select(.linkedHandlerFqn|test("ApiController"))] | length'
```

**Pass:** ≥1

---

## RS-GAP-01b — Duplicate path dedup loses second controller

**Priority:** P0  
**Status:** Open  
**Requirement:** TRG-16

### Symptom

Manual graph has **two** handlers for `POST /receipt/submit`:

- `ReceiptSubmitServiceApiController` (default)
- `CAReceiptSubmitServiceApiController` (`CouponsAffiliate` channel)

TestSeer shows **one** entry (CA interface wins dedup).

### Root cause

REST `triggerId = actor:httpMethod:path`. Dedup key does not include handler class → second trigger dropped in `EntryTriggerOrchestrator`.

### Fix

Include `linkedHandlerFqn` (or interface FQN pre-linker) in dedup key — Phase 5 Step 2.

### Validation

```bash
curl -s "http://localhost:8080/v1/facts/entry-triggers?orgId=quotient&serviceId=df14f277-8b47-4687-a0fd-6a3f5a4b64a6" \
  | jq '[.data[] | select(.pathPattern=="/receipt/submit" and .httpMethod=="POST")] | length'
```

**Pass:** ≥2

---

## RS-GAP-01c — Request-param routing not modeled

**Priority:** P1  
**Status:** Open  
**Requirement:** TRG-17

### Symptom

Manual E5/E6: Shopmium scan/correct use `@PostMapping(params = "shopmium")` on same base paths as other variants. TestSeer cannot distinguish them.

### Root cause

Mapping `params` not parsed into trigger attributes or dedup key.

### Fix

Parse `params` into `attributes.requestParams` — Phase 5 Step 3.

### Validation

```bash
curl -s "http://localhost:8080/v1/facts/entry-triggers?orgId=quotient&serviceId=df14f277-8b47-4687-a0fd-6a3f5a4b64a6" \
  | jq '[.data[] | select(.pathPattern|test("/receipt/scan|/receipt/correct")) | {path: .pathPattern, params: .attributes.requestParams}]'
```

**Pass:** Shopmium rows include `requestParams`.

---

## RS-GAP-02 — Shallow reachability from REST handler chain

**Priority:** P0  
**Status:** Open  
**Requirement:** GRP-19

### Symptom

```bash
curl -s ".../reachability?type=class&symbolFqn=...ReceiptSubmitServiceApiController"
# → nodes: 1, edges: 1
```

Manual graph: controller → `ReceiptSubmissionService` → DAOs → Kafka producer (~20 edges).

### Root cause

1. Interface methods have no body → no `INVOKES` from interface seed.  
2. Even on controller, reachability may not traverse injected service calls if seed or edges incomplete.

### Fix

Interface→impl bridge + BFS seed resolution — Phase 5 Step 4. Related to closed TE-GAP-02 hydration but **different seed class** (REST vs Kafka listener).

### Validation

**Pass:** Controller reachability ≥15 nodes, ≥20 edges.

---

## RS-GAP-03 — Outbound facts not attributed to receipt-service caller

**Priority:** P1  
**Status:** Open  
**Requirement:** OUT-08

### Symptom

`GET /v1/facts/outbound?packagePrefix=com.quotient.platform.receiptservice` → 0 rows.

Producers (`SalesTransactionEventProducer`, etc.) indexed under `receipt-common`.

### Root cause

Outbound linker sets caller on producer class, not on invoking controller/service method in `receipt-service` module.

### Fix

Caller context propagation via `INVOKES` chain — Phase 5 Step 5.

### Validation

**Pass:** ≥1 outbound row with `callerClassFqn` under `receiptservice`.

---

## RS-GAP-04 — No gates under receiptservice packagePrefix

**Priority:** P2  
**Status:** Open  
**Requirement:** GATE-09

### Symptom

`GET /v1/facts/gates?packagePrefix=com.quotient.platform.receiptservice` → 0.

Manual graph lists kafka topic enable flags and feature toggles.

### Root cause

Gates may be on beans in `receipt.common` or yaml-only keys not associated with `receiptservice` prefix filter.

### Fix

Extend gate query with wired-module / autowire hop — Phase 5 Step 6.

### Validation

**Pass:** ≥3 gates on prefix **or** documented alternate prefix query returning enable keys.

---

## RS-GAP-05 — Suite module triggers vs K8s workload (scope)

**Priority:** —  
**Status:** Scope difference  
**Requirement:** —

### Symptom

Full monorepo index includes `receipt-service-suite` Kafka consumers and cron jobs not deployed as `receipt-service` K8s workload.

### Resolution

Document in pilot manual graph. Use `packagePrefix=com.quotient.platform.receiptservice` for workload-scoped queries. No TestSeer code change required.

---

## RS-GAP-06 — Flow-diagram downstream eval gaps (expected)

**Priority:** —  
**Status:** Expected  
**Requirement:** —

### Symptom

`flow-diagram?packagePrefix=...` reports 4 gaps on eval/redemption downstream topics.

### Resolution

Cross-repo BFS stops at unindexed or external eval services — same class as TE pilot terminal gaps. No Phase 5 fix.

---

## RS-GAP-07 — Reverse impact empty for RestController handler

**Priority:** P2  
**Status:** Open  
**Requirement:** TRG-13-R2

### Symptom

```bash
curl -s ".../entry-flow/impact?handlerFqn=...ReceiptSubmitServiceApiController.submitReceipt"
# → triggers: []
```

Works only if querying exact interface FQN stored in DB (and even then may miss after dedup fix).

### Root cause

TRG-13 shipped for Kafka/REST with **stored** handler FQN only. No impl↔interface alias lookup.

### Fix

Extend reverse impact query — Phase 5 Step 7.

### Validation

**Pass:** Impact on impl **and** interface handler FQN both return ≥1 trigger.

---

## Cross-reference: TE vs RS gaps

| Theme | Transaction-eval (TE-GAP) | Receipt-service (RS-GAP) |
|-------|---------------------------|--------------------------|
| Ingress type | Kafka `@KafkaListener` | REST interface+controller |
| Handler linking | Class vs method tier (TE-GAP-03) | Interface vs impl (RS-GAP-01) |
| Reachability depth | Hydration (TE-GAP-02) **closed** | REST seed + interface bridge (RS-GAP-02) |
| Egress | Kafka producer (TE-GAP-04) | Cross-module producer (RS-GAP-03) |
| Reverse impact | Method FQN normalization | Impl/interface alias (RS-GAP-07) |

---

## Sign-off checklist

- [ ] RS-GAP-01 closed (TRG-15)
- [ ] RS-GAP-01b closed (TRG-16)
- [ ] RS-GAP-01c closed (TRG-17)
- [ ] RS-GAP-02 closed (GRP-19)
- [ ] RS-GAP-03 closed (OUT-08)
- [ ] RS-GAP-04 closed or documented alternate (GATE-09)
- [ ] RS-GAP-07 closed (TRG-13-R2)
- [ ] `ReceiptServiceSuiteGraphIT` green
- [ ] DesignDocuments pilot docs refreshed
