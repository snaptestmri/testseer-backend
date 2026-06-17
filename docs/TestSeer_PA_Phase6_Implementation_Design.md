# TestSeer PA Phase 6 — Remaining Partner Adapter Graph Gaps (Implementation Design)

> **Status:** Implemented (2026-06-16)  
> **Issue registry:** [32-partner-adapter-graph-gap-issues.md](features/32-partner-adapter-graph-gap-issues.md)  
> **Prior phase:** [TestSeer_PA_Phase5_Implementation_Design.md](TestSeer_PA_Phase5_Implementation_Design.md)  
> **Pilot evidence:** [PartnerAdapterSuite_ServiceGraph_GapAnalysis.md](../../../../Downloads/DesignDocuments/Docs/PartnerAdapterSuite_ServiceGraph_GapAnalysis.md)

---

## 1. Executive summary

Phase 6 closes **P2/P3 backlog** from the partner-adapter pilot after Phase 5 P1 fixes:

| Step | Issue | Deliverable |
|------|-------|-------------|
| **1** | PA-GAP-04 | `quotient-messaging.yml` terminal topics: `*.PARTNER_ADAPTER_NOTIFICATION`, `*.UMO_EVENT` |
| **2** | PA-GAP-05 | Reversed FREEDOM literal `codeGateRule` (`"FREEDOM".equalsIgnoreCase(...)`) |
| **3** | PA-GAP-06 | Delete-before-insert for `external_endpoint_facts` / `external_call_site_facts`; `@JsonAlias("resolvedUrl")` on API |
| **4** | — | Unit tests + issue registry + pilot doc updates |

**Deferred:** PA-GAP-09 (Micrometer/Actuator egress — same class as TE-GAP-09).

---

## 2. Root cause analysis

### 2.1 PA-GAP-04 — cross-repo `NO_SUBSCRIBER` on prod topic variants

Cross-repo trace on `DEV_T.PARTNER_ADAPTER_NOTIFICATION` walks env aliases (`T.*`, `PDN_T.*`, etc.). Prod `T.PARTNER_ADAPTER_NOTIFICATION` and `T.UMO_EVENT` have no subscriber in the `quotient-full` bundle — correctly **external boundaries**, but were classified as `NO_SUBSCRIBER` because terminal topic rules only covered `*.ASTRA` and `*.PARTNER_NOTIFICATION`.

**Fix:** Add terminal topic globs for partner-adapter egress topics so `CrossRepoGapClassifier` emits `TERMINAL_EXTERNAL`.

### 2.2 PA-GAP-05 — Freedom gate missing on HyveeOfferAdapter

Production adapters use the reversed literal form:

```java
isFreedomOffer = "FREEDOM".equalsIgnoreCase(offer.getInsertedBy());
```

The existing rule-pack pattern expected `FREEDOM.equalsIgnoreCase(...)` (static constant first). Hyvee was also unindexed before Phase 5 JAVA_17 parser fix.

**Fix:** Add `"FREEDOM"\.equalsIgnoreCase\([^)]*getInsertedBy\(\)` to `codeGateRules`. `SKIP_IF_FALSE` on `if (!isFreedomOffer)` still emits `isFreedomOffer=true` CODE_FLAG on both adapters.

### 2.3 PA-GAP-06 — external endpoint URLs null after re-index

Three contributing factors:

1. **API field name:** Response field is `urlResolved`; pilot validation curls used `resolvedUrl` (always empty in jq filter).
2. **Stale rows:** `external_endpoint_facts` used `ON CONFLICT DO NOTHING` without delete-on-reindex; first index with `url_resolved=null` blocked URL updates when unique key differed by URL column.
3. **Parser/index:** Hyvee adapter was not fully indexed until Phase 5 — linker could not attach `callerClassFqn`.

**Fix:** Mirror `pubsub_resource_facts` delete-before-insert for external endpoint tables; add `@JsonAlias("resolvedUrl")` for backward-compatible curls.

---

## 3. Implementation map

| Component | Path | Role |
|-----------|------|------|
| `quotient-messaging.yml` | `config/rule-packs/` | Terminal topics + reversed FREEDOM gate |
| `DualWriteService.writeExternalEndpointFacts` | `ingestion/` | Delete + insert on re-index |
| `DualWriteService.writeExternalCallSiteFacts` | `ingestion/` | Delete + insert on re-index |
| `FactQueryController.ExternalEndpointFactView` | `query/` | `@JsonAlias("resolvedUrl")` |
| `CrossRepoGapClassifierTest` | `src/test/.../query/` | PA-GAP-04 terminal classification |
| `FlowGateExtractorTest` | `src/test/.../messaging/` | PA-GAP-05 reversed FREEDOM |
| `YamlExternalEndpointExtractorTest` | `src/test/.../external/` | PA-GAP-06 dev yaml URLs |
| `DualWriteServiceTest` | `src/test/.../ingestion/` | PA-GAP-06 re-index URL replace |

---

## 4. Validation curls

```bash
ORG=quotient; BASE=http://localhost:8080
SVC=387ac4be-bc9a-4445-9e73-dc60b1daa39c   # post Phase 5 re-index

# PA-GAP-04 — prod topic variants classified TERMINAL_EXTERNAL, not NO_SUBSCRIBER
curl -s "$BASE/v1/graph/event-flow/cross-repo?orgId=$ORG&env=dev&shortId=DEV_T.PARTNER_ADAPTER_NOTIFICATION&bundle=quotient-full" \
  | jq '[.data.gaps[] | select(.gapType=="NO_SUBSCRIBER" and (.topicShortId|test("PARTNER_ADAPTER|UMO")))] | length'
# Pass: 0

# PA-GAP-05 — Freedom gates on Hyvee + NCR
curl -s "$BASE/v1/facts/gates?orgId=$ORG&serviceId=$SVC&packagePrefix=com.quotient.platform.partneradapter" \
  | jq '[.data[] | select(.gateKey=="insertedBy" or (.gateKey|test("isFreedomOffer")))] | map(.guardedSymbolFqn) | unique'
# Pass: includes HyveeOfferAdapter and NcrBspOfferAdapter

# PA-GAP-06 — resolved URLs (either field name works)
curl -s "$BASE/v1/facts/external-endpoints?orgId=$ORG&serviceId=$SVC&env=dev" \
  | jq '[.data[] | select(.urlResolved != null)] | length'
# Pass: >= 2 (Hyvee offer-endpoint + OIS partner-publish-details)
```

After code deploy, **re-index** partner-adapter-suite and flush Redis if routing/gates look stale (`redis-cli FLUSHALL` on local dev).

---

## 5. Test plan

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
cd testseer-backend
mvn test -Dtest=CrossRepoGapClassifierTest,TopicGlobMatcherTest,FlowGateExtractorTest,YamlExternalEndpointExtractorTest,DualWriteServiceTest
```
