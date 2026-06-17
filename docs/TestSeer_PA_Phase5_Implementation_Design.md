# TestSeer PA Phase 5 — Partner Adapter Graph Hardening (Implementation Design)

> **Status:** Implemented (2026-06-16)  
> **Issue registry:** [32-partner-adapter-graph-gap-issues.md](features/32-partner-adapter-graph-gap-issues.md)  
> **Pilot evidence:** [PartnerAdapterSuite_ServiceGraph_GapAnalysis.md](../../../../Downloads/DesignDocuments/Docs/PartnerAdapterSuite_ServiceGraph_GapAnalysis.md) · [Manual](../../../../Downloads/DesignDocuments/Docs/PartnerAdapterSuite_ServiceGraph_Manual.md) · [TestSeer](../../../../Downloads/DesignDocuments/Docs/PartnerAdapterSuite_ServiceGraph_TestSeer.md)  
> **Pattern reference:** [TestSeer_BL050_P0_Implementation_Design.md](TestSeer_BL050_P0_Implementation_Design.md) (transaction-eval) · [TestSeer_RS_Phase5_Implementation_Design.md](TestSeer_RS_Phase5_Implementation_Design.md) (receipt-service)  
> **Author / date:** 2026-06-16

---

## 1. Executive summary

Phase 5 closes **P1 graph gaps** from the **riq-partner-adapter-suite** pilot (Phases 0–4). The service is a **Pub/Sub + REST consumer** with **Spring list-injection factory routing** (`PartnerAdapterFactory`) and **Pub/Sub publish egress** via `PubSubMsgGateway.sendByteArrayToPubSub`.

| Step | Issue | Deliverable |
|------|-------|-------------|
| **1** | PA-GAP-03 | `ListInjectionFactoryRoutingEnricher` + `GraphFactProjector.projectListInjectionFactoryRoutes()` |
| **2** | PA-GAP-02 | `ROUTES_TO` edges factory → 3 adapters (reachability from consumer) |
| **3** | PA-GAP-01 | `PubSubPublishOutboundExtractor` — PUBSUB outbound facts with topic short id |
| **4** | PA-GAP-03 | `quotient-routing.yml` metadata for `PartnerAdapterFactory` |
| **5** | PA-GAP-02b | JavaParser `JAVA_17` — `HyveeOfferAdapter` switch expressions |
| **6** | — | Unit + integration tests |

**Deferred (unchanged):** PA-GAP-04 (bundle prod subscribers), PA-GAP-05 (Freedom gate duplication), PA-GAP-06 (external URL resolution), PA-GAP-09 (metrics).

---

## 2. Scope

### In scope

- PA-GAP-01, PA-GAP-02, PA-GAP-03
- Rule-pack routing metadata
- Tests and issue registry
- Pilot doc updates after re-index

### Out of scope

- Cross-repo bundle index for prod `T.*` / `S.*` topics (PA-GAP-04)
- Duplicate `CODE_FLAG` on `HyveeOfferAdapter` (PA-GAP-05 — rule pack already has FREEDOM pattern on NCR)
- ConfigMap URL unwrap for external-endpoints (PA-GAP-06 — separate linker/env work)
- Micrometer egress (PA-GAP-09)

---

## 3. Pilot constants

```bash
ORG=quotient
BASE=http://localhost:8080
SVC=362a7510-f668-4b79-aeb5-5f35fe5b79ae
PKG=com.quotient.platform.partneradapter
REPO_PATH=/Users/mrinalthigale/Documents/GitHub/riq-partner-adapter-suite
FACTORY=com.quotient.platform.partneradapter.lib.factory.PartnerAdapterFactory
CONSUMER=com.quotient.platform.partneradapter.consumer.PartnerAdapterConsumer
HYVEE=com.quotient.platform.partneradapter.lib.adapter.HyveeOfferAdapter
```

Re-index before validation:

```bash
curl -s -X POST "$BASE/admin/index/local" \
  -H 'Content-Type: application/json' \
  -d "{\"orgId\":\"$ORG\",\"path\":\"$REPO_PATH\",\"serviceModuleId\":\"partner-adapter-suite\"}"
```

---

## 4. Baseline vs target

| Surface | Baseline (pilot 2026-06-16) | Phase 5 target |
|---------|-----------------------------|----------------|
| `/graph/routing` for `PartnerAdapterFactory` | `factories: []` | ≥1 factory, **3** routing keys |
| Reachability from `PartnerAdapterConsumer` | 20 nodes; no Hyvee adapter | includes `HyveeOfferAdapter`, `NcrBspOfferAdapter`, `DGAdapter` |
| Reachability from `HyveeOfferAdapter` | 0 nodes | >0 (adapter-local chain) |
| Outbound facts — Pub/Sub publish | HTTP rows only; no topic id | ≥2 rows with `httpMethod=PUBSUB` and `DEV_T.*` topic in `path` |
| `event-flow` publish hops | Already present | unchanged |
| Consistency / entry triggers | 3 scenarios, 8 triggers | unchanged |

---

## 5. Root cause analysis

### 5.1 PA-GAP-02 / PA-GAP-03 — list-injection factory map

`PartnerAdapterFactory` registers adapters at construction:

```java
public PartnerAdapterFactory(List<BaseAdapter> adapters) {
    this.adapterEventProcessorMap = adapters.stream()
        .collect(Collectors.toMap(BaseAdapter::getAdapterName, Function.identity(), (a, b) -> b));
}
public BaseAdapter getProcessor(String adapterName) { ... }
```

Existing `FactoryRoutingExtractor` only handles `@PostConstruct` + explicit `map.put(key, bean)` (transaction-eval `ProcessorFactory` pattern). The **Collectors.toMap + method reference** pattern was invisible.

**Fix:** `ListInjectionFactoryRoutingEnricher` detects `Collectors.toMap` + `::getAdapterName` + `List<>` constructor param, finds implementors (`implements BaseAdapter`, `extends OfferBaseAdapter`), infers routing keys from `getClass().getSimpleName()` or `DgConstants.ADAPTER_NAME`, and emits `FactoryRoutingDef` rows. `GraphFactProjector` projects `ROUTES_TO` edges and `routing_table_facts`.

### 5.2 PA-GAP-01 — Pub/Sub publish not in outbound facts

`FreedomOfferUpdateEventPublisher` and `PartnerAdapterPublishService` call `PubSubMsgGateway.sendByteArrayToPubSub(topic, bytes)`. Kafka publish extraction existed; Pub/Sub did not.

**Fix:** `PubSubPublishOutboundExtractor` scans `MethodCallDef` for `sendByteArrayToPubSub`, resolves topic via linked `PubSubResourceFact` (class link or `pubsub.publisher.topicId.{leaf}` spring key), emits `OutboundCallFact` with `httpMethod=PUBSUB` and topic short id in `path` (mirrors Kafka pattern).

---

## 6. Implementation map

| Component | Path | Role |
|-----------|------|------|
| `ListInjectionFactoryRoutingEnricher` | `ingestion/graph/` | Detect list-injection factory routes |
| `PubSubPublishOutboundExtractor` | `ingestion/messaging/` | PUBSUB outbound facts |
| `JavaParserService` (JAVA_17) | `ingestion/` | Parse switch expressions in `HyveeOfferAdapter` |
| `GraphFactProjector.projectListInjectionFactoryRoutes` | `graph/` | `ROUTES_TO` + routing_table_facts |
| `MessagingFactOrchestrator` | `ingestion/messaging/` | Merge pubsub outbound into outbound pipeline |
| `quotient-routing.yml` | `config/rule-packs/` | Factory metadata (`getProcessor`, `String`) |
| `ListInjectionFactoryRoutingEnricherTest` | `src/test/.../graph/` | Unit |
| `PubSubPublishOutboundExtractorTest` | `src/test/.../messaging/` | Unit |
| `PartnerAdapterSuiteGraphIT` | `src/test/.../graph/` | Integration baseline |

---

## 7. Validation curls

```bash
ORG=quotient; BASE=http://localhost:8080
SVC=362a7510-f668-4b79-aeb5-5f35fe5b79ae
FACTORY=com.quotient.platform.partneradapter.lib.factory.PartnerAdapterFactory

# PA-GAP-03
curl -s "$BASE/v1/graph/routing?orgId=$ORG&serviceId=$SVC&factoryFqn=$FACTORY" \
  | jq '.data.factories[0].routes | length'
# Pass: >= 3

# PA-GAP-02
curl -s "$BASE/v1/graph/reachability?orgId=$ORG&serviceId=$SVC&type=class&symbolFqn=com.quotient.platform.partneradapter.consumer.PartnerAdapterConsumer" \
  | jq '[.data.nodes[].symbolFqn | select(test("HyveeOfferAdapter|NcrBspOfferAdapter|DGAdapter"))]'
# Pass: 3 adapter FQNs

# PA-GAP-01
curl -s "$BASE/v1/facts/outbound?orgId=$ORG&serviceId=$SVC" \
  | jq '[.data[] | select(.httpMethod=="PUBSUB" and (.path|test("UMO|PARTNER_ADAPTER")))] | length'
# Pass: >= 2
```

---

## 8. Test plan

| Test | Asserts |
|------|---------|
| `ListInjectionFactoryRoutingEnricherTest` | Detects Collectors.toMap factory; infers Hyvee + DG keys |
| `PubSubPublishOutboundExtractorTest` | Links freedomumo topic to UMO short id |
| `PartnerAdapterSuiteGraphIT` | Consumer reachability includes factory + Hyvee via ROUTES_TO |

Run:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
cd testseer-backend
mvn test -Dtest=ListInjectionFactoryRoutingEnricherTest,PubSubPublishOutboundExtractorTest,PartnerAdapterSuiteGraphIT
```