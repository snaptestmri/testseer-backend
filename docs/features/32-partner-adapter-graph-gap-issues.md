# Partner Adapter Graph — Open Issues Registry (PA-GAP pilot)

> **Status:** Pilot sign-off (2026-06-16) — PA-GAP-01–03 **Closed**; PA-GAP-04–06, 09 deferred  
> **Implementation design:** [TestSeer_PA_Phase5_Implementation_Design.md](../TestSeer_PA_Phase5_Implementation_Design.md)  
> **Evidence:** [Gap analysis](../../../../../../Downloads/DesignDocuments/Docs/PartnerAdapterSuite_ServiceGraph_GapAnalysis.md) · [Manual graph](../../../../../../Downloads/DesignDocuments/Docs/PartnerAdapterSuite_ServiceGraph_Manual.md) · [TestSeer graph](../../../../../../Downloads/DesignDocuments/Docs/PartnerAdapterSuite_ServiceGraph_TestSeer.md)  
> **Pilot index:** `serviceId` `362a7510-f668-4b79-aeb5-5f35fe5b79ae` · commit `d48f7df5` · registry `partner-adapter-suite`

Use this registry when implementing or closing partner-adapter graph gaps. Each issue has a **root cause**, **fix**, and **validation** curl with pass criteria.

### Pilot constants

```bash
ORG=quotient
BASE=http://localhost:8080
SVC=362a7510-f668-4b79-aeb5-5f35fe5b79ae
PKG=com.quotient.platform.partneradapter
FACTORY=com.quotient.platform.partneradapter.lib.factory.PartnerAdapterFactory
CONSUMER=com.quotient.platform.partneradapter.consumer.PartnerAdapterConsumer
REPO_PATH=/Users/mrinalthigale/Documents/GitHub/riq-partner-adapter-suite
```

Re-index before validation:

```bash
curl -s -X POST "$BASE/admin/index/local" \
  -H 'Content-Type: application/json' \
  -d "{\"orgId\":\"$ORG\",\"path\":\"$REPO_PATH\",\"serviceModuleId\":\"partner-adapter-suite\"}"
```

---

## Issue index

| ID | Summary | Pri | Status |
|----|---------|-----|--------|
| [PA-GAP-01](#pa-gap-01-pubsub-publish-missing-from-outbound-facts) | Pub/Sub publish missing from outbound facts | P1 | **Closed** (2026-06-16) |
| [PA-GAP-02](#pa-gap-02-adapter-reachability-from-consumer) | Hyvee adapter not reachable from consumer | P1 | **Closed** (2026-06-16) |
| [PA-GAP-03](#pa-gap-03-factory-routing-api-empty) | `/graph/routing` empty for PartnerAdapterFactory | P1 | **Closed** (2026-06-16) |
| [PA-GAP-04](#pa-gap-04-cross-repo-prod-subscriber-gaps) | `NO_SUBSCRIBER` on prod `T.*` topics | P2 | Open (bundle index) |
| [PA-GAP-05](#pa-gap-05-freedom-gate-on-hyvee-adapter) | Freedom CODE_FLAG only on NCR adapter | P3 | Deferred |
| [PA-GAP-06](#pa-gap-06-external-endpoint-urls-null) | external-endpoints URLs null | P2 | Open |
| [PA-GAP-09](#pa-gap-09-metrics-egress-not-modeled) | Micrometer not an exit edge | P3 | Deferred |

---

## PA-GAP-01: Pub/Sub publish missing from outbound facts

**Status:** Closed (2026-06-16)

### Symptom

Manual exits **X1** (PARTNER_ADAPTER topic) and **X2** (UMO topic) use `PubSubMsgGateway.sendByteArrayToPubSub`. `event-flow` shows publish hops, but `GET /v1/facts/outbound` returns HTTP rows only — no topic short id on publish call sites.

### Root cause

Outbound pipeline indexed RestTemplate/WebClient calls. No extractor for `PubSubMsgGateway.sendByteArrayToPubSub` (unlike Kafka `KafkaPublishOutboundExtractor` added in TE Phase 5).

### Fix

`PubSubPublishOutboundExtractor` — wired in `MessagingFactOrchestrator.mergeOutboundFacts()`.

### Validation

```bash
curl -s "$BASE/v1/facts/outbound?orgId=$ORG&serviceId=$SVC" \
  | jq '[.data[] | select(.httpMethod=="PUBSUB" and (.path|test("UMO|PARTNER_ADAPTER")))]'
```

**Pass:** ≥2 rows with topic short ids (e.g. `DEV_T.UMO_EVENT`, `DEV_T.PARTNER_ADAPTER_NOTIFICATION`).

---

## PA-GAP-02: Adapter reachability from consumer

**Status:** Closed (2026-06-16)

### Symptom

Reachability from `PartnerAdapterConsumer` shows factory and clients (20 nodes) but **not** `HyveeOfferAdapter`, `NcrBspOfferAdapter`, or `DGAdapter`. Direct query on `HyveeOfferAdapter` returns 0 nodes.

### Root cause

Dynamic dispatch `partnerAdapterFactory.getProcessor(name).execute()` is not resolved by static `INVOKES` extraction. Factory→adapter edges were never projected because list-injection map pattern was undetected.

### Fix

`ListInjectionFactoryRoutingEnricher` + `GraphFactProjector.projectListInjectionFactoryRoutes()` emit `ROUTES_TO` edges from factory to each adapter implementor.

### Validation

```bash
curl -s "$BASE/v1/graph/reachability?orgId=$ORG&serviceId=$SVC&type=class&symbolFqn=$CONSUMER" \
  | jq '[.data.nodes[].symbolFqn | select(test("HyveeOfferAdapter|NcrBspOfferAdapter|DGAdapter"))]'
```

**Pass:** all 3 adapter FQNs present.

---

## PA-GAP-03: Factory routing API empty

**Status:** Closed (2026-06-16)

### Symptom

Manual §7 documents three adapter keys (`HyveeOfferAdapter`, `NcrBspOfferAdapter`, `DGAdapter`). `GET /v1/graph/routing?factoryFqn=...PartnerAdapterFactory` returns empty `factories`.

### Root cause

Same as PA-GAP-02 — `FactoryRoutingExtractor` only handles `@PostConstruct` + `map.put` pattern.

### Fix

Same enricher + `routing_table_facts` projection; `quotient-routing.yml` entry for selector `getProcessor` / discriminator `String`.

### Validation

```bash
curl -s "$BASE/v1/graph/routing?orgId=$ORG&serviceId=$SVC&factoryFqn=$FACTORY" \
  | jq '.data.factories[0].routes | map(.routingKey)'
```

**Pass:** length ≥ 3; keys include `HyveeOfferAdapter`, `NcrBspOfferAdapter`, `DGAdapter`.

---

## PA-GAP-04: Cross-repo prod subscriber gaps

**Status:** Closed (2026-06-16, Phase 6)

### Symptom

Cross-repo trace on `DEV_T.PARTNER_ADAPTER_NOTIFICATION` shows 4 terminal `NO_SUBSCRIBER` gaps on prod `T.*` / `S.*` topic variants.

### Root cause

Prod topic aliases (`T.PARTNER_ADAPTER_NOTIFICATION`, `T.UMO_EVENT`) have no subscriber in `quotient-full` — external boundaries — but terminal topic rules only covered ASTRA / PARTNER_NOTIFICATION patterns.

### Fix

Added `crossRepoTrace.terminalTopics` in `quotient-messaging.yml`:

- `*.PARTNER_ADAPTER_NOTIFICATION`
- `*.UMO_EVENT`

### Validation

```bash
curl -s "$BASE/v1/graph/event-flow/cross-repo?orgId=$ORG&env=dev&shortId=DEV_T.PARTNER_ADAPTER_NOTIFICATION&bundle=quotient-full" \
  | jq '[.data.gaps[] | select(.gapType=="NO_SUBSCRIBER" and (.topicShortId|test("PARTNER_ADAPTER|UMO")))] | length'
```

**Pass:** 0 (prod variants show `TERMINAL_EXTERNAL`).

---

## PA-GAP-05: Freedom gate on Hyvee adapter

**Status:** Closed (2026-06-16, Phase 6)

### Symptom

`isFreedomOffer=true` CODE_FLAG appears on `NcrBspOfferAdapter` only; Hyvee path also checks `insertedBy == FREEDOM`.

### Root cause

Production code uses `"FREEDOM".equalsIgnoreCase(offer.getInsertedBy())` — reversed literal not matched by `FREEDOM.equalsIgnoreCase(...)` rule. Hyvee was unindexed before Phase 5 JAVA_17 fix.

### Fix

Added reversed-literal `codeGateRule` in `quotient-messaging.yml`.

### Validation

```bash
curl -s "$BASE/v1/facts/gates?orgId=$ORG&serviceId=$SVC&packagePrefix=com.quotient.platform.partneradapter" \
  | jq '[.data[] | select(.guardedSymbolFqn|test("HyveeOfferAdapter|NcrBspOfferAdapter")) | select(.gateKey=="insertedBy" or (.gateKey|test("isFreedomOffer")))] | length'
```

**Pass:** ≥2 gates across both adapter FQNs.

---

## PA-GAP-06: External endpoint URLs null

**Status:** Closed (2026-06-16, Phase 6)

### Symptom

`GET /v1/facts/external-endpoints?env=dev` returns Hyvee/OIS config keys but URLs appear null when filtering on `resolvedUrl`.

### Root cause

1. API field is `urlResolved` (not `resolvedUrl`).
2. `external_endpoint_facts` used `ON CONFLICT DO NOTHING` without delete-on-reindex — stale null URL rows persisted.
3. Hyvee adapter unindexed before Phase 5 blocked linker `callerClassFqn`.

### Fix

- Delete-before-insert for `external_endpoint_facts` and `external_call_site_facts` on re-index.
- `@JsonAlias("resolvedUrl")` on `ExternalEndpointFactView.urlResolved`.

### Validation

```bash
curl -s "$BASE/v1/facts/external-endpoints?orgId=$ORG&serviceId=$SVC&env=dev" \
  | jq '[.data[] | select(.urlResolved != null)] | length'
```

**Pass:** ≥2 non-null URLs (Hyvee offer-endpoint + OIS partner-publish-details).

---

## PA-GAP-09: Metrics egress not modeled

**Status:** Deferred

Same class as TE-GAP-09 / RS-GAP-09 — Actuator/prometheus not projected as exit edges.

---

## Tests

| Test | Covers |
|------|--------|
| `ListInjectionFactoryRoutingEnricherTest` | PA-GAP-02/03 enricher |
| `PubSubPublishOutboundExtractorTest` | PA-GAP-01 |
| `PartnerAdapterSuiteGraphIT` | PA-GAP-02 reachability baseline |
| `CrossRepoGapClassifierTest` | PA-GAP-04 terminal topics |
| `FlowGateExtractorTest` | PA-GAP-05 reversed FREEDOM gate |
| `YamlExternalEndpointExtractorTest` | PA-GAP-06 dev yaml URL extraction |
| `DualWriteServiceTest` | PA-GAP-06 re-index URL replace |
