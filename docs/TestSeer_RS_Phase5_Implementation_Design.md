# TestSeer RS Phase 5 — Receipt Service Graph Hardening (Implementation Design)

> **Status:** Implemented (2026-06-16)  
> **Backlog:** **BL-061** — REST ingress graph hardening **Done**  
> **Issue registry:** [30-receipt-service-graph-gap-issues.md](features/30-receipt-service-graph-gap-issues.md)  
> **Pilot evidence:** [ReceiptService_ServiceGraph_GapAnalysis.md](../../../../Downloads/DesignDocuments/Docs/ReceiptService_ServiceGraph_GapAnalysis.md) · [Manual](../../../../Downloads/DesignDocuments/Docs/ReceiptService_ServiceGraph_Manual.md) · [TestSeer](../../../../Downloads/DesignDocuments/Docs/ReceiptService_ServiceGraph_TestSeer.md)  
> **Pattern reference:** [TestSeer_BL050_P0_Implementation_Design.md](TestSeer_BL050_P0_Implementation_Design.md) (Kafka consumer pilot) · [11-entry-triggers.md](features/11-entry-triggers.md)  
> **Author / date:** 2026-06-16

---

## 1. Executive summary

Phase 5 closes **REST-service graph gaps** discovered on the **receipt-service** pilot — the second `service-graph-pilot` run after transaction-eval-consumer (Kafka consumer).

Transaction-eval validated **Kafka ingress + egress + reachability hydration**. Receipt-service exposes a different topology: **interface-first Spring MVC** (`*ServiceApi` + `*ApiController`), **shared-module producers** (`receipt-common`), and **duplicate path mappings** (default vs `CouponsAffiliate` channel).

| Step | Issue | Req | Deliverable |
|------|-------|-----|-------------|
| **1** | RS-GAP-01 | TRG-15 | `RestControllerImplementationLinker` — REST trigger → `@RestController` impl FQN |
| **2** | RS-GAP-01b | TRG-16 | Disambiguate duplicate `pathPattern` + HTTP method (multi-controller) |
| **3** | RS-GAP-01c | TRG-17 | Include `@*Mapping(params=…)` in trigger identity |
| **4** | RS-GAP-02 | GRP-19 | Bridge interface handler → impl method for `INVOKES` / reachability |
| **5** | RS-GAP-03 | OUT-08 | Attribute `receipt-common` Kafka producers to calling `receiptservice` handler |
| **6** | RS-GAP-04 | GATE-09 | Gate facts visible when filtering `packagePrefix` spans wired modules |
| **7** | RS-GAP-07 | TRG-13-R2 | Reverse impact resolves impl + interface handler FQNs |

**Already sufficient on pilot (no Phase 5 work):**

- Kafka egress X1–X4 (`event-flow` steps present)
- Cross-repo submission trace (12 hops)
- Data-access edges (88 on `receiptservice`)
- Consistency scenarios (19)
- Flow-diagram composer with `packagePrefix` (54 nodes; 4 downstream eval gaps expected)
- RS-GAP-05 (suite cron/Kafka noise) — document + filter only
- RS-GAP-06 (terminal eval topic gaps) — expected cross-repo BFS

---

## 2. Scope

### In scope

- RS-GAP-01, 01b, 01c, 02, 03, 04, 07
- Pilot acceptance on `platform-receipt-service` after re-index
- Unit + integration tests (`ReceiptServiceSuiteGraphIT`)
- Doc updates: [11-entry-triggers.md](features/11-entry-triggers.md), issue registry, OpenAPI field notes

### Out of scope (defer)

- RS-GAP-09 metrics egress (same as TE-GAP-09)
- Full OpenAPI ↔ implementation reconciliation (BL-046 extension)
- Gradle dependency tree (BL-059)
- Live REST caller discovery (NGW routing tables) — RS-GAP-07 documents **indexed** reverse lookup only

---

## 3. Pilot constants

```bash
ORG=quotient
BASE=http://localhost:8080
SVC=df14f277-8b47-4687-a0fd-6a3f5a4b64a6
PKG=com.quotient.platform.receiptservice
REPO_PATH=/Users/mrinalthigale/Documents/GitHub/platform-receipt-service

# Primary business ingress (manual E1)
HANDLER_IMPL=com.quotient.platform.receiptservice.web.api.ReceiptSubmitServiceApiController.submitReceipt
HANDLER_IFACE=com.quotient.platform.receiptservice.web.api.ReceiptSubmitServiceApi.submitReceipt
CA_HANDLER_IMPL=com.quotient.platform.receiptservice.web.api.CAReceiptSubmitServiceApiController.submitReceipt
ANCHOR=handlerFqn:${HANDLER_IMPL}
SUBMISSION_TOPIC=QUOT.RECEIPTS.SUBMISSIONS.EVENTS
```

Re-index before validation:

```bash
curl -s -X POST "$BASE/admin/index/local" \
  -H 'Content-Type: application/json' \
  -d "{\"orgId\":\"$ORG\",\"path\":\"$REPO_PATH\",\"serviceId\":\"$SVC\"}"
```

---

## 4. Baseline vs target (receipt-service module)

| Surface | Baseline (2026-06-16) | Phase 5 target |
|---------|-------------------------|----------------|
| REST `/receipt/submit` POST | `linkedHandlerFqn` = **interface** `CAReceiptSubmitServiceAPI`; default controller **missing** | ≥1 row with **impl** `ReceiptSubmitServiceApiController.submitReceipt` |
| REST `/receipt/scan` POST | interface `ReceiptScanApi` only | impl `ReceiptScanApiController` + `params=shopmium` disambiguator |
| Reverse impact on impl handler | `triggers: []` | ≥1 `REST_INBOUND` on `/receipt/submit` |
| Reachability from impl controller | 1 node, 1 edge | ≥15 nodes, ≥20 edges through `ReceiptSubmissionService` |
| Reachability from service class | 1 node | includes producers/helpers in `receiptservice` + attributed common producers |
| Outbound facts (`receiptservice` prefix) | 0 | ≥2 (OCR REST, submission Kafka attributed) |
| Gates (`receiptservice` prefix) | 0 | ≥3 kafka enable keys **or** documented cross-package gate query |
| Flow-diagram gaps (anchor submit) | 4 downstream eval topics | unchanged (RS-GAP-06 expected) |

---

## 5. Root cause analysis (corrected from pilot)

### 5.1 RS-GAP-01 is not “zero handlers”

`InboundRestTriggerExtractor` **does** set `linked_handler_fqn` — but to the **API interface** class where `@RequestMapping` lives, not the `@RestController` implementation.

Receipt-service pattern:

```java
// ReceiptSubmitServiceApi.java — endpoints indexed here
@RequestMapping("/receipt/submit")
public interface ReceiptSubmitServiceApi {
    @PostMapping ResponseEntity<...> submitReceipt(...);
}

// ReceiptSubmitServiceApiController.java — runtime handler, often no repeated mapping annotations
@RestController
public class ReceiptSubmitServiceApiController implements ReceiptSubmitServiceApi { ... }
```

Agents querying `ReceiptSubmitServiceApiController.submitReceipt` get **no reverse impact** because DB stores `ReceiptSubmitServiceApi`.

### 5.2 RS-GAP-01b — trigger dedup collision

`EntryTriggerOrchestrator.addTriggers` dedupes by `triggerId`. For REST, `triggerId = actor:httpMethod:path` — **identical** for `ReceiptSubmitServiceApi` and `CAReceiptSubmitServiceAPI` on `POST /receipt/submit`. Only one survives (CA wins in current index order).

### 5.3 RS-GAP-01c — param routing invisible

`@PostMapping(params = "shopmium")` on `/receipt/scan` and `/receipt/correct` is not part of trigger identity → cannot distinguish Shopmium vs future variants.

### 5.4 RS-GAP-02 — reachability stops at interface/empty body

`GraphFactProjector` emits `INVOKES` from **method bodies**. Interface methods have no body; controller `@Override` methods contain the orchestration calls. Reachability seeded from interface or under-linked controller yields **1 node**.

### 5.5 RS-GAP-03 — producers in sibling module

`SalesTransactionEventProducer`, `ShopmiumEUEventProducer` live in `receipt-common`. Outbound linker sets `callerClassFqn` on producer class, not on `WorkbenchReceiptCorrectionApiController` / `ReceiptScanApiController`.

---

## 6. Implementation steps

### Step 1 — RS-GAP-01: RestControllerImplementationLinker (TRG-15)

**Priority:** First — unblocks entry-flow, flow-diagram anchor, reverse impact.

#### Design

New ingestion component (mirror `CronHandlerLinker`):

```
RestControllerImplementationLinker.link(triggers, models):
  For each REST_INBOUND trigger with linkedHandlerFqn = interface FQN:
    Find ParsedModel where:
      - annotations contain RestController
      - implements clause includes interface simple/FQN name
      - has @Override method matching linkedMethod (same name)
    If found:
      - Set linkedHandlerFqn := controller.classFqn
      - Set linkedMethod unchanged
      - Add attributes.implementingInterface := interface FQN
      - evidenceSource := REST_IMPL_LINKER
```

Wire in `EntryTriggerOrchestrator` **after** `inboundRestTriggerExtractor`, **before** graph projection.

Persist optional column or JSON attribute — no Flyway required if stored in `attributes` JSONB:

```json
{
  "handlerInterfaceFqn": "…ReceiptSubmitServiceApi",
  "handlerImplFqn": "…ReceiptSubmitServiceApiController",
  "linker": "REST_IMPL"
}
```

#### Files (expected)

| File | Change |
|------|--------|
| `ingestion/triggers/RestControllerImplementationLinker.java` | **new** |
| `ingestion/triggers/EntryTriggerOrchestrator.java` | invoke linker |
| `graph/EntryTriggerGraphProjector.java` | prefer impl FQN for TRIGGER→CLASS edges |
| `ingestion/triggers/RestControllerImplementationLinkerTest.java` | **new** |

#### Acceptance

```bash
curl -s "$BASE/v1/facts/entry-triggers?orgId=$ORG&serviceId=$SVC" \
  | jq '[.data[] | select(.pathPattern=="/receipt/submit" and .httpMethod=="POST") |
        {handler: .linkedHandlerFqn, attrs: .attributes}]'
```

**Pass:** At least one row with `linkedHandlerFqn` ending in `ReceiptSubmitServiceApiController`.

---

### Step 2 — RS-GAP-01b: Multi-controller path disambiguation (TRG-16)

#### Design

Extend REST trigger dedup key in `InboundRestTriggerExtractor` / orchestrator:

```
dedupeKey = triggerId + "|" + env + "|" + httpMethod + "|" + path + "|" + linkedHandlerFqn
```

Alternatively include `linkedHandlerFqn` in `triggerId` suffix when path collision detected.

Emit **both**:

| path | method | handler (after Step 1) |
|------|--------|------------------------|
| `/receipt/submit` | POST | `ReceiptSubmitServiceApiController` |
| `/receipt/submit` | POST | `CAReceiptSubmitServiceApiController` |

#### Acceptance

```bash
curl -s "$BASE/v1/facts/entry-triggers?orgId=$ORG&serviceId=$SVC" \
  | jq '[.data[] | select(.pathPattern=="/receipt/submit" and .httpMethod=="POST") | .linkedHandlerFqn] | length'
```

**Pass:** `length >= 2`.

---

### Step 3 — RS-GAP-01c: Mapping params in trigger identity (TRG-17)

#### Design

Parse `@PostMapping(params = "shopmium")` / `@GetMapping(params = …)` into:

- `attributes.requestParams` — e.g. `"shopmium"` or `"CouponsAffiliate"`
- Append to `pathPattern` display as `/receipt/scan?shopmium` **or** add `queryPattern` field on `EntryTriggerView` (prefer attribute first to avoid API break)

Rule-pack override in `quotient-triggers.yml` for receipt Shopmium actor if needed.

#### Acceptance

Distinct triggers for `/receipt/scan` vs `/receipt/correct` with param metadata; manual E5/E6 mappable.

---

### Step 4 — RS-GAP-02: Interface→impl reachability bridge (GRP-19)

#### Design

During `GraphFactProjector.projectModel`:

1. When class implements interface I and overrides method m:
   - Add `INVOKES` edge: `I#m` → `Controller#m` (synthetic bridge), **or**
   - Duplicate `INVOKES` targets from impl method body onto interface method node for query seeding

2. `GraphQueryController.reachability`:
   - When `symbolFqn` is interface method, resolve to impl method via linker index before BFS
   - When `type=class` on interface, union reachability from all impl classes

3. `FlowDiagramAnchorResolver`: accept `handlerFqn` on **either** interface or impl (like TE-GAP-03 dot vs hash fix)

#### Acceptance

```bash
curl -s "$BASE/v1/graph/reachability?orgId=$ORG&serviceId=$SVC&type=class&symbolFqn=com.quotient.platform.receiptservice.web.api.ReceiptSubmitServiceApiController" \
  | jq '{nodes: (.data.nodes|length), edges: (.data.edges|length)}'

curl -s "$BASE/v1/graph/reachability?orgId=$ORG&serviceId=$SVC&type=class&symbolFqn=com.quotient.platform.receiptservice.service.ReceiptSubmissionService" \
  | jq '{nodes: (.data.nodes|length), edges: (.data.edges|length)}'
```

**Pass:** Controller ≥15 nodes / ≥20 edges; service class ≥10 nodes (includes DAO + producer callees).

---

### Step 5 — RS-GAP-03: Cross-module egress attribution (OUT-08)

#### Design

Extend Kafka / HTTP outbound synthesis:

```
When producer P in module M_common is invoked from handler method H in module M_service:
  outbound_fact.callerClassFqn = H (controller or service method)
  outbound_fact.producerClassFqn = P
  pubsub_resource_facts.linked_class_fqn = P (unchanged)
```

Implementation hooks:

- `GraphFactProjector` — trace `INVOKES` from impl controller → service → producer field injection calls
- `MessagingClassLinker` — propagate caller context from stack of `INVOKES` edges (bounded depth 6)
- `ExternalEndpointLinker` — same caller attribution for `rest.apis.*` config clients

#### Acceptance

```bash
curl -s "$BASE/v1/facts/outbound?orgId=$ORG&serviceId=$SVC" \
  | jq '[.data[] | select(.callerClassFqn|test("receiptservice")) | {caller: .callerClassFqn, kind: .outboundKind, path: .pathPattern}] | .[0:5]'
```

**Pass:** ≥1 row with caller under `ReceiptSubmitServiceApiController` or `ReceiptSubmissionService` and Kafka/HTTP kind.

---

### Step 6 — RS-GAP-04: Gate projection across wired modules (GATE-09)

#### Design

Option A (query-only): `GET /v1/facts/gates?packagePrefix=X` includes gates where:

- `symbolFqn` starts with prefix, **or**
- gate's `@ConditionalOnProperty` bean is `@Autowired` into a class matching prefix (1-hop)

Option B (index): duplicate gate facts onto handler classes (heavier).

Prefer **Option A** first — extend gate query SQL parallel to `PackagePrefixFilter`.

Include kafka topic enable keys from yaml (`kafka.topics.receipt.submission.enabled`) already parsed for messaging — surface as `NO_BEAN` gates on producer beans.

#### Acceptance

```bash
curl -s "$BASE/v1/facts/gates?orgId=$ORG&serviceId=$SVC&env=dev&packagePrefix=$PKG" \
  | jq '[.data[] | {symbol: .symbolFqn, gateKey, effect}] | .[0:5]'
```

**Pass:** ≥3 gates OR documented query using `packagePrefix=com.quotient.platform.receipt` returning kafka enable keys.

---

### Step 7 — RS-GAP-07: Reverse impact handler resolution (TRG-13-R2)

#### Design

Extend `EntryFlowService.queryReverseImpact` (same family as TE-GAP-03):

1. Parse `handlerFqn` as `Class.method` / `Class#method`
2. Lookup triggers where `linked_handler_fqn = class` **OR** `attributes->>'handlerInterfaceFqn' = class` **OR** `attributes->>'handlerImplFqn' = class`
3. Match `linked_method` when method tier provided

#### Acceptance

```bash
curl -s "$BASE/v1/graph/entry-flow/impact?orgId=$ORG&serviceId=$SVC&handlerFqn=$HANDLER_IMPL" \
  | jq '.data.triggers | length'

curl -s "$BASE/v1/graph/entry-flow/impact?orgId=$ORG&serviceId=$SVC&handlerFqn=$HANDLER_IFACE" \
  | jq '.data.triggers | length'
```

**Pass:** Both ≥1 for POST `/receipt/submit`.

---

## 7. Test plan

| Layer | Artifact | Covers |
|-------|----------|--------|
| Unit | `RestControllerImplementationLinkerTest` | interface→impl, no impl, multiple impls |
| Unit | `InboundRestTriggerExtractorTest` | params disambiguation |
| Unit | `GraphFactProjectorTest` (extend) | interface bridge edges |
| IT | `ReceiptServiceSuiteGraphIT` | full pilot curls as `@Test` methods |
| Fixture | `ReceiptServiceGraphFixture.java` | minimal interface+controller+service+producer graph |

Mirror structure of `TransactionEvalSuiteGraphIT` / `TransactionEvalGraphFixture`.

---

## 8. Master validation script (§5 sign-off)

```bash
ORG=quotient
BASE=http://localhost:8080
SVC=df14f277-8b47-4687-a0fd-6a3f5a4b64a6
PKG=com.quotient.platform.receiptservice
HANDLER=com.quotient.platform.receiptservice.web.api.ReceiptSubmitServiceApiController.submitReceipt

echo "=== RS-AC-1 REST impl linked ==="
curl -s "$BASE/v1/facts/entry-triggers?orgId=$ORG&serviceId=$SVC" \
  | jq '[.data[] | select(.linkedHandlerFqn|test("ReceiptSubmitServiceApiController"))] | length'

echo "=== RS-AC-2 Dual POST /receipt/submit ==="
curl -s "$BASE/v1/facts/entry-triggers?orgId=$ORG&serviceId=$SVC" \
  | jq '[.data[] | select(.pathPattern=="/receipt/submit" and .httpMethod=="POST")] | length'

echo "=== RS-AC-3 Reverse impact ==="
curl -s "$BASE/v1/graph/entry-flow/impact?orgId=$ORG&serviceId=$SVC&handlerFqn=$HANDLER" \
  | jq '.data.triggers | length'

echo "=== RS-AC-4 Reachability ==="
curl -s "$BASE/v1/graph/reachability?orgId=$ORG&serviceId=$SVC&type=class&symbolFqn=com.quotient.platform.receiptservice.web.api.ReceiptSubmitServiceApiController" \
  | jq '{nodes: (.data.nodes|length), edges: (.data.edges|length)}'

echo "=== RS-AC-5 Submission egress (unchanged) ==="
curl -s "$BASE/v1/graph/event-flow?orgId=$ORG&serviceId=$SVC&shortId=QUOT.RECEIPTS.SUBMISSIONS.EVENTS" \
  | jq '{steps: (.data.steps|length), gaps: (.data.gaps|length)}'

echo "=== RS-AC-6 Flow diagram ==="
curl -s "$BASE/v1/graph/flow-diagram?orgId=$ORG&serviceId=$SVC&anchor=handlerFqn:$HANDLER&packagePrefix=$PKG" \
  | jq '{nodes: .data.nodes|length, edges: .data.edges|length, gaps: .data.gaps|length}'

echo "=== RS-AC-7 Cross-repo ==="
curl -s "$BASE/v1/graph/event-flow/cross-repo?orgId=$ORG&env=dev&shortId=QUOT.RECEIPTS.SUBMISSIONS.EVENTS&bundle=quotient-full&followMode=runtime" \
  | jq '{hops: (.data.hops|length), gaps: (.data.gaps|length)}'
```

**Pilot sign-off when:** RS-AC-1 ≥1, RS-AC-2 ≥2, RS-AC-3 ≥1, RS-AC-4 nodes ≥15 edges ≥20, RS-AC-5 steps ≥2, RS-AC-6 gaps ≤4, RS-AC-7 hops ≥10.

**Live validation (2026-06-17):** serviceId `68d48766-3d8f-4351-a09e-0f30a509eb32` · **6/7 pass** — RS-AC-4 fails (1/1 reachability on full-repo index). **Conditional sign-off**; see [GapAnalysis §14](../../../../Downloads/DesignDocuments/Docs/ReceiptService_ServiceGraph_GapAnalysis.md).

---

## 9. Documentation updates (post-implementation)

| Doc | Update |
|-----|--------|
| [ReceiptService_ServiceGraph_TestSeer.md](../../../../Downloads/DesignDocuments/Docs/ReceiptService_ServiceGraph_TestSeer.md) | Refresh counts, close RS-GAP rows |
| [ReceiptService_ServiceGraph_GapAnalysis.md](../../../../Downloads/DesignDocuments/Docs/ReceiptService_ServiceGraph_GapAnalysis.md) | Correct RS-GAP-01 root cause; sign-off date |
| [11-entry-triggers.md](features/11-entry-triggers.md) | Document interface→impl linker |
| [service-graph-pilot/SKILL.md](../../../../Downloads/DesignDocuments/Skills/service-graph-pilot/SKILL.md) | Add REST-service pilot notes under limitations |
| [docs/BACKLOG.md](../../docs/BACKLOG.md) | Add BL-061 Done entry |

---

## 10. Relationship to BL-050 / BL-054

| BL-050 / BL-054 capability | Receipt pilot lesson |
|----------------------------|---------------------|
| Kafka egress linker | Works — not Phase 5 focus |
| Reachability hydration | Works for **Kafka consumer** seed; REST **interface** seed needs GRP-19 |
| flow-diagram composer | Works with impl `handlerFqn` anchor after Step 1 |
| packagePrefix filter | Works — keep using for workload scope |
| TRG-13 reverse impact | Kafka fixed in TE-GAP-03; REST needs TRG-13-R2 |

Phase 5 generalizes patterns for **all Optimus interface+controller REST services** (OIS, redemption-service, receipt-service, partner adapters).

---

## 11. One-line verdict

Phase 5 is **REST ingress parity**: make TestSeer treat `@RestController` implementations as first-class handlers, disambiguate Optimus dual-mapping controllers, and extend reachability/outbound attribution across **receipt-service → receipt-common** — without re-building Kafka graph infrastructure from BL-050.
