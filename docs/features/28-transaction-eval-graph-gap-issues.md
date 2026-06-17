# Transaction Eval Graph — Open Issues Registry (BL-050 / BL-054 pilot)

> **Status:** Pilot sign-off (2026-06-16) — TE-GAP-01–08, TE-GAP-10 **Closed**; TE-GAP-09 deferred  
> **Backlog:** [BL-050](../../../docs/BACKLOG.md) · [BL-054](../../../docs/BACKLOG.md) — **Done**  
> **Evidence:** [Gap analysis](../../../../../../Downloads/DesignDocuments/Docs/TransactionEvalConsumer_ServiceGraph_GapAnalysis.md) · [Manual graph](../../../../../../Downloads/DesignDocuments/Docs/TransactionEvalConsumer_ServiceGraph_Manual.md) · [TestSeer graph](../../../../../../Downloads/DesignDocuments/Docs/TransactionEvalConsumer_ServiceGraph_TestSeer.md)  
> **Pilot index:** `serviceId` `3756095a-e423-4aeb-b11a-fe6d3340fca5` · commit `0bdc0be4` · indexed `quotient-full` · registry `transaction-eval-suite`

Use this registry when implementing or closing BL-050 / BL-054. Each issue has a **root cause**, **requirements**, and **validation** curls with pass criteria.

### Pilot constants

```bash
ORG=quotient
BASE=http://localhost:8080
SVC=3756095a-e423-4aeb-b11a-fe6d3340fca5   # quotient-full index 2026-06-16
HANDLER=com.quotient.platform.transaction.eval.consumer.TransactionEvalConsumer
METHOD=processSalesCanonicalEvent
HANDLER_FQN=${HANDLER}.${METHOD}
CONSUMER_PKG=com.quotient.platform.transaction.eval
PIPELINE=QUOT.SALES.TRANSACTION.PIPELINE.EVENTS
```

Re-index before validation:

```bash
curl -s -X POST "$BASE/admin/index/local" \
  -H 'Content-Type: application/json' \
  -d "{\"orgId\":\"$ORG\",\"path\":\"/Users/mrinalthigale/Documents/GitHub/platform-transaction-eval-consumer\",\"serviceId\":\"$SVC\"}"
# Use serviceId from response index-result.json for all curls below.
```

---

## Issue index

| ID | Summary | Backlog | Req | Pri | Status |
|----|---------|---------|-----|-----|--------|
| [TE-GAP-01](#te-gap-01-kafka-egress-x1x5-missing-from-event-flow) | Kafka publish hops X1–X5 absent from event-flow | BL-050 | KFK-02, KFK-03 | P0 | **Closed** (2026-06-16) |
| [TE-GAP-02](#te-gap-02-reachability-nodeids-without-projected-edges) | 121 `nodeIds`, empty `nodes`/`edges` | BL-050 | KFK-04, GRP-12 | P0 | **Closed** (2026-06-16) · [design](TestSeer_TE_GAP_02_Reachability_Hydration_Design.md) |
| [TE-GAP-03](#te-gap-03-reverse-impact-empty-for-kafka-handler) | `entry-flow/impact` → `triggers: []` despite T1 | BL-050, BL-054 | TRG-13-R, SFD-18 | P0 | **Closed** (2026-06-16) |
| [TE-GAP-04](#te-gap-04-cross-repo-pipeline--notification-gaps) | `NO_PUBLISHER` / `NO_SUBSCRIBER` on pipeline & notification | BL-050, BL-051 | KFK-03, MSG-05 | P1 | **Closed** (2026-06-16) |
| [TE-GAP-05](#te-gap-05-cron-triggers-unlinked-to-handlers) | 4 `CRON_K8S` triggers, no handler link | BL-050 | KFK-05 | P1 | **Closed** (2026-06-16, 4/4 crons) |
| [TE-GAP-06](#te-gap-06-external-endpoints-not-resolved) | `external-endpoints` empty for Workbench / PubSub API | BL-050 | KFK-06 | P1 | **Closed** (2026-06-16) |
| [TE-GAP-07](#te-gap-07-factsby-file-returns-empty) | `GET /v1/facts/by-file` → `[]` for indexed Java | BL-050 | KFK-07 | P2 | **Closed** (2026-06-16) |
| [TE-GAP-08](#te-gap-08-suite-cron-noise-without-module-filter) | Suite crons appear as consumer ingress | BL-050, BL-054 | KFK-08, GRP-17 | P2 | **Closed** (2026-06-16) |
| [TE-GAP-09](#te-gap-09-metrics-egress-x8-not-modeled) | Micrometer publish not an exit edge | BL-054 | SFD-15, GRP-18 | P3 | Deferred |
| [TE-GAP-10](#te-gap-10-no-composed-service-flow-diagram) | No single manual §6-equivalent API | BL-054 | SFD-01–SFD-20 | P1 | **Closed** (2026-06-16) |
| [TE-GAP-11](#te-gap-11-cross-repo-bfs-manifest-fan-out) | BFS follows manifest into unrelated topic trees | BL-055 | MSG-12, KFK-03-R6 | P1 | **Closed** (2026-06-16) |
| [TE-GAP-12](#te-gap-12-cross-repo-hop-participant-dedupe) | Duplicate pub/sub rows per hop; gaps lack `hopOrder` | BL-056, BL-049 | MSG-13, VIZ-33 | P1 | **Closed** (2026-06-16) |
| [TE-GAP-13](#te-gap-13-terminal-external-topic-gap-taxonomy) | `NO_SUBSCRIBER` on manifest-only / external topics | BL-057 | MSG-14, KFK-03-R6 | P1 | **Closed** (2026-06-16) |

---

## TE-GAP-01: Kafka egress (X1–X5) missing from event-flow

**Status:** Closed (2026-06-16) — all five egress topics return `publishOutbounds >= 1` on `event-flow?env=dev`; pilot `serviceId` `3756095a-e423-4aeb-b11a-fe6d3340fca5`.

### Symptom

Manual exits **X1–X5** (processed, redeem, fraud-rules, pattern-check, reward-status Kafka topics) are named in code. TestSeer indexes producer **classes** (`StxnProcessedEventProducer`, `FraudRulesEvaluationEventProducer`, etc.) but `GET /v1/graph/event-flow` returns **0 steps** for those topic short ids.

### Root cause

1. **Option C messaging path is Pub/Sub-first** — `MessagingFlowService` builds hops from `pubsub_resource_facts`; Kafka yaml topics and `AsyncProducer`/`KafkaTemplate` send sites are not written as publish resources at index time.
2. **`MessagingClassLinker`** does not map suite `*EventProducer` beans to `kafka.topics.*` short ids from Spring YAML.
3. **No `PUBLISHES_TO` graph edges** from handler/producer classes to Kafka topic nodes — so event-flow and cross-repo trace cannot traverse egress.

### Requirements

| ID | Requirement | Must |
|----|-------------|------|
| KFK-02-R1 | Extract `kafka.topics.*` from `application*.yaml` (topic name, consumer group, producer bean name) into messaging resource facts with `transport=KAFKA` | Must |
| KFK-03-R1 | Link each producer class to its yaml topic short id via `@Qualifier`, bean name, or `MessagingClassLinker` heuristic | Must |
| KFK-03-R2 | `GET /v1/graph/event-flow?shortId=<topic>` returns ≥1 hop with `role=PUBLISHER` and `serviceId` matching transaction-eval-suite for: processed, redeem, fraud, pattern, reward-status topics used by eval | Must |
| KFK-03-R3 | Project `PUBLISHES_TO` edges in `graph_edges` from producer/handler to Kafka topic node | Must |
| KFK-03-R4 | Event-flow hop includes `linkedClassFqn` pointing at the indexed producer class | Should |

### Manual mapping (acceptance targets)

| Manual | Producer class | Yaml / topic key (verify in repo) |
|--------|----------------|-----------------------------------|
| X1 | `StxnProcessedEventProducer` | `kafka.topics.stxn.processed` or env alias |
| X2 | `RebateRedeemEventProducer` (suite) | redeem topic key |
| X3 | `RewardStatusEventProducer` (suite) | reward-status topic key |
| X4 | `FraudRulesEvaluationEventProducer` | fraud topic key |
| X5 | `PatternCheckEventProducer` | pattern topic key |

### Validation

```bash
# After re-index — replace TOPIC_SHORT_ID per row above
for TOPIC in QUOT.SALES.TRANSACTION.PROCESSED.EVENTS QUOT.REBATE.REDEEM.EVENTS; do
  echo "=== $TOPIC ==="
  curl -s "$BASE/v1/graph/event-flow?orgId=$ORG&serviceId=$SVC&shortId=$TOPIC" \
    | jq '.data.steps | length, .[0].role, .[0].linkedClassFqn'
done
```

**Pass:** Each topic returns `steps | length >= 1`, first step `role` is `PUBLISHER` (or equivalent), `linkedClassFqn` contains `Producer`.

**Fail:** `steps == []` or only `SUBSCRIBER` hops with no publisher from eval suite.

---

## TE-GAP-02: Reachability `nodeIds` without projected edges

**Status:** Closed (2026-06-16) — `reachability?type=class` returns `nodes=121`, `edges=405`; edge to `TransactionEvaluationService` present. Automated: `TransactionEvalSuiteGraphIT`. **Design:** [TestSeer_TE_GAP_02_Reachability_Hydration_Design.md](../TestSeer_TE_GAP_02_Reachability_Hydration_Design.md).

### Symptom

`GET /v1/graph/reachability?type=class&symbolFqn=...TransactionEvalConsumer` returns **121 `nodeIds`** but **`nodes: []`** and **`edges: []`**.

### Root cause

1. **BL-053** extracts `INVOKES` / `routing_table_facts` and populates dependency **IDs** for neighborhood-style scans, but **`GraphProjector` does not hydrate** `graph_nodes` / `graph_edges` for the reachability response path (or projection runs but query layer only returns ID list).
2. Agents calling `type=service` + bare `serviceId` get cross-service `CALLS` only (documented in GRP-12) — but even with `type=class` + `symbolFqn`, edge materialization is empty.
3. Manual graph **ED001–ED028** orchestration edges exist only in the manual doc, not in Postgres `graph_edges`.

### Requirements

| ID | Requirement | Must |
|----|-------------|------|
| KFK-04-R1 | `MethodCallGraphExtractor` / `CallGraphProjector` persist `INVOKES` edges from `TransactionEvalConsumer` → `TransactionEvaluationService` → processors | Must |
| KFK-04-R2 | `GET /v1/graph/reachability?type=class&symbolFqn=<consumer>` returns non-empty `nodes[]` and `edges[]` with `depth >= 1` | Must |
| KFK-04-R3 | Reachability includes suite helpers reachable from consumer (e.g. `TransactionHelper`, producers) when indexed in same service | Should |
| GRP-12-R1 | API docs and `gaps[]` (BL-054) state that `type=service` without `symbolFqn` is not intra-service call graph | Must |

### Validation

```bash
curl -s "$BASE/v1/graph/reachability?orgId=$ORG&serviceId=$SVC&type=class\
&symbolFqn=com.quotient.platform.transaction.eval.consumer.TransactionEvalConsumer" \
  | jq '{nodeCount: (.data.nodeIds | length), nodes: (.data.nodes | length), edges: (.data.edges | length)}'

curl -s "$BASE/v1/graph/reachability?orgId=$ORG&serviceId=$SVC&type=method\
&symbolFqn=com.quotient.platform.transaction.eval.consumer.TransactionEvalConsumer#processSalesCanonicalEvent" \
  | jq '.data.edges[0:3]'
```

**Pass:** `nodes >= 5`, `edges >= 4`; at least one edge from consumer class to `TransactionEvaluationService`.

**Validated (quotient-full re-index, `serviceId` `3756095a-e423-4aeb-b11a-fe6d3340fca5`):** `nodeIds=121`, `nodes=121`, `edges=405`, 4 edges to `TransactionEvaluationService`.

**Fail:** `nodes == 0` or `edges == 0` while `nodeIds > 0`.

---

## TE-GAP-03: Reverse impact empty for Kafka handler

**Status:** Closed (2026-06-16) — `entry-flow/impact?handlerFqn=...processSalesCanonicalEvent` returns 1 `KAFKA_SUBSCRIBE` on pipeline topic.

### Symptom

Forward entry **T1** links `KAFKA_SUBSCRIBE` on `PIPELINE.EVENTS` → `TransactionEvalConsumer.processSalesCanonicalEvent`. Reverse query returns **no triggers**:

```bash
GET /v1/graph/entry-flow/impact?handlerFqn=...processSalesCanonicalEvent → triggers: []
```

### Root cause

1. **TRG-13** shipped for Pub/Sub and REST handlers; **regression or gap for `KAFKA_SUBSCRIBE`**: `linked_handler_fqn` may store class-only while impact query passes `Class.method`, or method tier does not match.
2. **`TRIGGERED_BY` graph edge** may exist in `entry_trigger_facts` but **not** in reverse lookup SQL for `trigger_kind=KAFKA_SUBSCRIBE`.
3. Optional `serviceId` filter may exclude rows if trigger registered under suite id but query uses stale UUID.

### Requirements

| ID | Requirement | Must |
|----|-------------|------|
| TRG-13-R1 | `entry-flow/impact` with `handlerFqn=<class>#<method>` returns ≥1 trigger when forward `entry-flow` shows `KAFKA_SUBSCRIBE` for same handler | Must |
| TRG-13-R2 | Impact hit includes `triggerKind=KAFKA_SUBSCRIBE`, `topicShortId` matching pipeline topic, `matchKind` in `EXACT` or `METHOD` | Must |
| SFD-18-R1 | Same fix required for flow-diagram ingress anchor and `gaps[]` must not list reverse-impact when T1 exists | Must |

### Validation

```bash
# Forward — must show T1
curl -s "$BASE/v1/graph/entry-flow?orgId=$ORG&serviceId=$SVC&triggerId=kafka:${PIPELINE,,}:${HANDLER,,}" \
  | jq '.data.triggers[0].triggerKind, .data.handlers[0].linkedMethod'

# Reverse — must mirror T1
curl -s "$BASE/v1/graph/entry-flow/impact?orgId=$ORG&serviceId=$SVC&handlerFqn=$HANDLER_FQN" \
  | jq '.data.triggers | length, .[0].triggerKind, .[0].topicShortId'

# Class-only handlerFqn should also match (tier 1)
curl -s "$BASE/v1/graph/entry-flow/impact?orgId=$ORG&serviceId=$SVC&handlerFqn=$HANDLER" \
  | jq '.data.triggers | length'
```

**Pass:** Reverse `triggers | length >= 1`; `triggerKind == "KAFKA_SUBSCRIBE"`; topic matches pipeline.

**Fail:** `triggers == []` while forward entry-flow shows linked Kafka trigger.

---

## TE-GAP-04: Cross-repo pipeline & notification gaps

**Status:** Closed (2026-06-16) — pipeline trace includes `transaction-eval-suite` subscriber, `gaps=[]`, `skippedExpansionCount=56`; notification hop `pubCount=2` unique services, `transport=HTTP_PUBSUB`.

### Symptom

`GET /v1/graph/event-flow/cross-repo?shortId=QUOT.SALES.TRANSACTION.PIPELINE.EVENTS` reports **`NO_SUBSCRIBER`** and/or **`NO_PUBLISHER`**. Notification topic `DEV_T.NOTIFICATION_REQ` shows **`NO_PUBLISHER`** despite BL-051 `HttpPubSubPublishLinker`.

### Root cause

1. **Bundle breadth** — `quotient-full` may not index upstream sales-transaction publisher repo at same commit/env lane.
2. **Kafka vs Pub/Sub join** — cross-repo BFS may not join Kafka subscriber facts with publisher repos (KFK-03).
3. **BL-051** virtual publisher facts require **re-index** after V21 migration; linker may not run for `PubSubNotificationClient` + `rest.apis.pubsub` yaml pair.
4. **Env lane mismatch** (`dev` vs `pdn`) between subscriber and publisher facts.

### Requirements

| ID | Requirement | Must |
|----|-------------|------|
| KFK-03-R5 | Cross-repo trace for pipeline topic lists transaction-eval-suite as **subscriber** hop when suite is indexed | Must |
| MSG-05-R1 | When publisher repo indexed in same bundle, trace must **not** emit `NO_SUBSCRIBER` for eval consumer | Must |
| BL-051-R1 | Cross-repo trace for `DEV_T.NOTIFICATION_REQ` (dev lane) shows HTTP_PUBSUB publisher from eval suite | Must |
| KFK-03-R6 | `FlowGap` / trace report distinguishes **index gap** vs **true orphan topic** | Should |

### Validation

```bash
curl -s "$BASE/v1/graph/event-flow/cross-repo?orgId=$ORG&env=dev&bundle=quotient-full&shortId=$PIPELINE" \
  | jq '.data.hops | map(.serviceId) , .data.gaps'

curl -s "$BASE/v1/graph/event-flow/cross-repo?orgId=$ORG&env=dev&bundle=quotient-full&shortId=DEV_T.NOTIFICATION_REQ" \
  | jq '.data.hops[] | select(.role=="PUBLISHER") | .serviceId'
```

**Pass:** Pipeline trace includes eval suite subscriber; notification trace includes eval publisher (or explicit gap only when publisher repo genuinely not in bundle).

**Fail:** `NO_SUBSCRIBER` on pipeline when eval indexed; `NO_PUBLISHER` on notification after BL-051 + re-index.

---

## TE-GAP-05: Cron triggers unlinked to handlers

**Status:** Closed (2026-06-16) — all four `evaluation-jobs` crons linked via `cronHandlerLinks` + `evaluation-jobs/*/src/main/java` in `transaction-eval-suite` sourceRoots; `CronHandlerLinker` heuristic extended for nested `*-job/src` paths and launcher class names.

### Symptom

Suite index surfaces **4 `CRON_K8S` triggers** (e.g. `stc-retry-job`) with **no `linked_handler_fqn`**.

### Root cause

1. **TRG-05** (K8s CronJob extraction) indexes cron metadata from argocd manifests but **does not link** cron → Spring Boot main / job module entry class.
2. Monorepo `transaction-eval-suite` includes **evaluation-jobs** modules outside consumer package — cron triggers are correct for suite scope but appear as **orphan ingress**.

### Requirements

| ID | Requirement | Must |
|----|-------------|------|
| KFK-05-R1 | For indexed CronJob → workload name match, set `linked_handler_fqn` to job module `@SpringBootApplication` or documented main class | Should |
| KFK-05-R2 | `GET /v1/graph/entry-flow?triggerId=cron:<jobName>` returns handler chain when link exists | Should |
| KFK-08-R1 | Document that crons are **suite-level** ingress, not consumer workload (manual boundary) | Must |

### Validation

```bash
curl -s "$BASE/v1/facts/entry-triggers?orgId=$ORG&serviceId=$SVC&kind=CRON_K8S" \
  | jq '.data[] | {triggerId, linkedHandlerFqn}'

curl -s "$BASE/v1/graph/entry-flow?orgId=$ORG&serviceId=$SVC&triggerId=cron:stc-retry-job" \
  | jq '.data.handlers | length'
```

**Pass:** At least one cron row has non-null `linkedHandlerFqn`; entry-flow from that trigger reaches job handler.

**Fail:** All crons `linkedHandlerFqn: null` after KFK-05 implementation.

---

## TE-GAP-06: External endpoints not resolved

**Status:** Closed (2026-06-16) — `facts/outbound` includes `PubSubNotificationClient` (`rest.apis.pubsub`, POST) and `WorkbenchSubmissionRest` (`/workbench/submission`, POST). `external-endpoints` populated after ConfigMap yaml unwrap (`YamlConfigUtils.expandAndFlatten`) + outbound path synthesis; **re-index** `quotient-full` to load facts.

### Symptom

Manual **X6** (Workbench) and **X7** (PubSub notification API) have full URL chains. TestSeer shows outbound **symbols** and yaml **paths** (`/workbench/submission`, `rest.apis.pubsub`) but `GET /v1/facts/external-endpoints` (or equivalent) is **empty**.

### Root cause

`ExternalEndpointResolver` does not resolve `rest.apis.*` / `rest-clients` yaml into concrete base URLs + path templates for query APIs.

### Requirements

| ID | Requirement | Must |
|----|-------------|------|
| KFK-06-R1 | Index `rest.apis.workbench` and `rest.apis.pubsub` from yaml with resolved URI placeholder keys | Should |
| KFK-06-R2 | Outbound facts for `PubSubNotificationClient` and Workbench client include `httpMethod`, `pathTemplate`, `configKey` | Should |
| KFK-06-R3 | Flow-diagram (BL-054) can emit HTTP exit nodes for X6/X7 from these facts | Should |

### Validation

```bash
curl -s "$BASE/v1/facts/outbound?orgId=$ORG&serviceId=$SVC" \
  | jq '.data[] | select(.symbolFqn | test("PubSubNotification|Workbench")) | {symbolFqn, path, httpMethod}'

curl -s "$BASE/v1/facts/external-endpoints?orgId=$ORG&serviceId=$SVC" \
  | jq 'length'
```

**Pass:** Outbound includes both clients with paths; external-endpoints ≥ 1 row OR documented equivalence via outbound until API merged.

---

## TE-GAP-07: `facts/by-file` returns empty

**Status:** Closed (2026-06-16) — `facts/by-file` returns `TransactionEvalConsumer` class FQN for repo-relative path.

### Symptom

`GET /v1/facts/by-file?path=.../TransactionEvalConsumer.java` returns `[]` despite class facts existing via `facts/class`.

### Root cause

By-file index key does not match ingestion path normalization (repo-relative vs absolute) or fact extractors do not tag `source_file` for graph/query join.

### Requirements

| ID | Requirement | Must |
|----|-------------|------|
| KFK-07-R1 | By-file query with repo-relative path returns class facts, entry triggers, and data-access rows for that compilation unit | Should |
| KFK-07-R2 | Accept absolute local path used at index time (normalize to repo-relative) | Should |

### Validation

```bash
REL=evaluation-consumers/transaction-eval-consumer/src/main/java/com/quotient/platform/transaction/eval/consumer/TransactionEvalConsumer.java
curl -s "$BASE/v1/facts/by-file?orgId=$ORG&serviceId=$SVC&path=$REL" | jq 'length'
curl -s "$BASE/v1/facts/class?orgId=$ORG&serviceId=$SVC&symbolFqn=$HANDLER" | jq '.data | length'
```

**Pass:** By-file `length >= 1` and includes class symbol for `TransactionEvalConsumer`.

---

## TE-GAP-08: Suite cron noise without module filter

**Status:** Closed (2026-06-16) — `packagePrefix=com.quotient.platform.transaction.eval` returns only `KAFKA_SUBSCRIBE` (count=1); no `CRON_K8S`.

### Symptom

Agents comparing **consumer-only** manual graph see 4 crons as ingress; manual doc scopes crons out.

### Root cause

`GET /v1/graph/entry-flow` and entry-triggers lack **`packagePrefix`** / `serviceModuleId` filter until KFK-08 / BL-054.

### Requirements

| ID | Requirement | Must |
|----|-------------|------|
| KFK-08-R2 | `packagePrefix=com.quotient.platform.transaction.eval` excludes evaluation-jobs cron triggers from consumer-scoped diagrams | Should |
| GRP-17-R1 | `GET /v1/graph/flow-diagram` honors `packagePrefix` for nodes and ingress set | Must |

### Validation

```bash
curl -s "$BASE/v1/facts/entry-triggers?orgId=$ORG&serviceId=$SVC&packagePrefix=$CONSUMER_PKG" \
  | jq '[.data[].triggerKind] | unique'

curl -s "$BASE/v1/graph/flow-diagram?orgId=$ORG&serviceId=$SVC&packagePrefix=$CONSUMER_PKG&anchor=handlerFqn:$HANDLER_FQN" \
  | jq '.data.ingress | map(.triggerKind) | unique'
```

**Pass:** Filtered ingress contains only `KAFKA_SUBSCRIBE` (no `CRON_K8S`) for consumer package prefix.

---

## TE-GAP-09: Metrics egress (X8) not modeled

### Symptom

`TransactionEvalMetricPublish` is indexed as a class; no exit edge to Micrometer / metrics backend.

### Root cause

No extractor for metrics publish as outbound/messaging; intentional non-goal for Option C unless extended.

### Requirements

| ID | Requirement | Must |
|----|-------------|------|
| SFD-15-R1 | `flow-diagram` `gaps[]` may include `METRICS_EGRESS` with manual reference X8 | Could |
| — | Full Micrometer modeling | Deferred |

### Validation

Deferred — document gap in flow-diagram only.

---

## TE-GAP-10: No composed service flow diagram

**Status:** Closed (2026-06-16) — `GET /v1/graph/flow-diagram` returns 133 nodes, 436 edges, `gaps=0`, valid Mermaid; MCP `testseer_get_service_flow_diagram`; viz Service Flow tab.

### Symptom

No single API produces manual **§6 Mermaid** (ingress → orchestration → routing → exits). Agents must merge `entry-flow`, `routing`, `reachability`, `event-flow` manually.

### Root cause

**BL-054** not implemented — `ServiceFlowDiagramComposer` spec only.

### Requirements

See [27-service-flow-diagram.md](27-service-flow-diagram.md) and [TestSeer_BL054_Service_Flow_Diagram_Design.md](../TestSeer_BL054_Service_Flow_Diagram_Design.md) — **SFD-01 through SFD-20**.

### Validation

```bash
curl -s "$BASE/v1/graph/flow-diagram?orgId=$ORG&serviceId=$SVC\
&anchor=handlerFqn:$HANDLER_FQN&packagePrefix=$CONSUMER_PKG&format=mermaid&depth=6" \
  | jq -r '.data.mermaid // .data.diagram' | head -40
```

**Pass:** HTTP 200; Mermaid contains Kafka ingress node, `TransactionEvaluationService`, processor fan-out (Default/Receipt/Corrected), and ≥1 exit; `gaps[]` lists TE-GAP-01/03 items until closed.

**Fail:** 404 or empty diagram.

---

## TE-GAP-11: Cross-repo BFS manifest fan-out

**Status:** Closed (2026-06-16) — `CrossRepoFollowPolicy` + `followMode=runtime` default; manifest-only repos never expand BFS in runtime mode; `skippedExpansionCount` on report. Pilot §9 CR-AC-1/2 pass on `quotient-full`.

**Design:** [TestSeer_BL055_BL057_CrossRepo_Trace_Hardening_Design.md](../TestSeer_BL055_BL057_CrossRepo_Trace_Hardening_Design.md#4-bl-055--bfs-scope-design) · **BL-055**

### Symptom

Cross-repo trace from `QUOT.SALES.TRANSACTION.PIPELINE.EVENTS` or `DEV_T.NOTIFICATION_REQ` reaches hop 12 with topics unrelated to the start flow (e.g. `PDN_T.*.ASTRA`, `DEV_T.PARTNER_NOTIFICATION`). `gaps[]` lists `NO_SUBSCRIBER` on those downstream topics while hop 1 is healthy.

### Root cause

BFS expands by **service**, not by causal chain: for every subscriber on hop *N*, **all** `PUBLISH` topics for that `serviceId` are enqueued. `platform-argocd-manifest` is indexed as a pipeline subscriber (rule-pack / YAML) and contributes hundreds of manifest-only publish facts with no runtime consumer.

### Requirements

| ID | Requirement | Pri |
|----|-------------|-----|
| MSG-12-R1 | BFS must not follow subscribers where `moduleType=catalog` or repo is manifest-only (`platform-argocd-manifest`) unless `?includeManifest=true` | Must |
| MSG-12-R2 | BFS follow set: subscribers with `linkedClassFqn` **or** matching `entry_trigger_facts` only | Must |
| MSG-12-R3 | Optional query param `maxDepth` / `followMode=causal` (default: topic neighborhood of start + one egress hop) | Should |
| KFK-03-R6 | `FlowGap` includes `hopOrder` + `topicShortId` (see BL-056) | Should |

### Validation

```bash
curl -s "$BASE/v1/graph/event-flow/cross-repo?orgId=$ORG&bundle=quotient-full&shortId=$PIPELINE&maxHops=3" \
  | jq '{hopTopics: [.data.hops[].topicShortId], gaps: .data.gaps}'
```

**Pass:** Hops ⊆ `{PIPELINE, eval egress kafka topics}`; no ASTRA topics within `maxHops=3` unless manifest explicitly included.

---

## TE-GAP-12: Cross-repo hop participant dedupe

**Status:** Closed (2026-06-16) — `CrossRepoFlowPresenter` dedupes hop participants by `serviceId+role`; `FlowGap.hopOrder` / `topicShortId`; `hopSummaries[]` + `narrative[]` on `CrossRepoFlowReport`; MCP + viz (`followMode` selector, trace meta pills, hop summaries panel). Pilot §9 CR-AC-4/5 pass.

### Symptom

Hop 1 notification trace shows 14 publisher rows but only 2 services (`transaction-eval-suite`, `platform-receipt-service`). Same `serviceName` repeated 20× on subscriber side for `optimus-offer-services-suite`.

### Root cause

`pubsub_resource_facts` stores one row per yaml file × env profile × linked class. Cross-repo returns raw fact rows as `PubSubOrgView` list items without dedupe by `(serviceId, shortId, role, linkedClassFqn)`.

### Requirements

| ID | Requirement | Pri |
|----|-------------|-----|
| MSG-13-R1 | Each `CrossRepoHop.publishers` / `.subscribers` unique by `(serviceId, role)` for display (highest confidence wins) | Must |
| MSG-13-R2 | `FlowGap` record adds `hopOrder`, `topicShortId` (BL-049); viz uses structured fields not description substring | Should |

### Validation

```bash
curl -s "$BASE/v1/graph/event-flow/cross-repo?orgId=$ORG&shortId=DEV_T.NOTIFICATION_REQ&maxHops=1" \
  | jq '.data.hops[0] | {pubServices: ([.publishers[].serviceName] | unique), pubCount: (.publishers|length)}'

# Human-readable summary (preferred for triage)
curl -s "$BASE/v1/graph/event-flow/cross-repo?orgId=$ORG&shortId=DEV_T.NOTIFICATION_REQ&maxHops=12" \
  | jq -r '.data.narrative[]'

curl -s "$BASE/v1/graph/event-flow/cross-repo?orgId=$ORG&shortId=DEV_T.NOTIFICATION_REQ&maxHops=12" \
  | jq '.data.hopSummaries[] | {order, topicShortId, summaryLine}'
```

**Pass:** `pubCount` equals `|unique serviceNames|` (or unique service+handler pairs).

---

## TE-GAP-13: Terminal / external topic gap taxonomy

**Status:** Closed (2026-06-16) — `CrossRepoGapClassifier` emits `MANIFEST_ONLY_PUBLISHER` / `TERMINAL_EXTERNAL` / `NO_SUBSCRIBER`; rule-pack `crossRepoTrace.terminalTopics` (`*.ASTRA`). Pilot §9 CR-AC-3 pass.

**Design:** [TestSeer_BL055_BL057_CrossRepo_Trace_Hardening_Design.md](../TestSeer_BL055_BL057_CrossRepo_Trace_Hardening_Design.md#5-bl-057--gap-taxonomy-design) · **BL-057**

### Symptom

`NO_SUBSCRIBER` on `PDN_T.ACTIVATE_OFFER.ASTRA`, `PDN_T.PAYMENT.HANDLER.ASTRA`, `PDN_T.REDEEM_OFFER.ASTRA` — publisher is manifest-only; real consumer is partner/external or not in `quotient-full`.

### Root cause

Gap detector treats all topics uniformly: publisher exists + zero `SUBSCRIBE` facts → `NO_SUBSCRIBER`. No distinction between **index miss** (should index consumer repo) vs **terminal boundary** (external partner, egress-only Kafka).

### Requirements

| ID | Requirement | Pri |
|----|-------------|-----|
| MSG-14-R1 | Emit `MANIFEST_ONLY_PUBLISHER` when all publishers have `evidenceSource=YAML` and empty `linkedClassFqn` | Must |
| MSG-14-R2 | Emit `TERMINAL_EXTERNAL` when topic matches partner/external pattern (e.g. `*.ASTRA`, rule-pack `terminalTopics`) and no bundle subscriber | Should |
| MSG-14-R3 | Reserve `NO_SUBSCRIBER` for topics where a runtime publisher exists but consumer repo is missing from bundle | Must |

### Validation

```bash
curl -s "$BASE/v1/graph/event-flow/cross-repo?orgId=$ORG&shortId=$PIPELINE&maxHops=12" \
  | jq '.data.gaps[] | select(.description|test("ASTRA")) | .gapType' | sort -u
```

**Pass:** Gap types are `MANIFEST_ONLY_PUBLISHER` or `TERMINAL_EXTERNAL`, not generic `NO_SUBSCRIBER`.

---

## Closure checklist (pilot sign-off)

Re-run after each BL merge + re-index:

| Step | Command / check | Issues closed |
|------|-----------------|---------------|
| 1 | `GET /v1/status/$SVC` → `CURRENT` | — |
| 2 | Pipeline event-flow + cross-repo | TE-GAP-01, TE-GAP-04 |
| 3 | Each egress topic event-flow | TE-GAP-01 |
| 4 | Reachability nodes/edges | TE-GAP-02 |
| 5 | entry-flow/impact | TE-GAP-03 |
| 6 | Cron linking or package filter | TE-GAP-05, TE-GAP-08 |
| 7 | outbound / external-endpoints | TE-GAP-06 |
| 8 | facts/by-file | TE-GAP-07 |
| 9 | flow-diagram mermaid | TE-GAP-10 |
| 10 | Cross-repo `followMode=runtime` — no ASTRA fan-out (§9 CR-AC-1) | TE-GAP-11 |
| 11 | Notification hop dedupe + `narrative[]` (§9 CR-AC-4/5) | TE-GAP-12 |
| 12 | ASTRA gaps typed `TERMINAL_EXTERNAL` / `MANIFEST_ONLY_PUBLISHER` (§9 CR-AC-3) | TE-GAP-13 |

When all P0/P1 rows pass, update [Gap analysis §13](../../../../../../Downloads/DesignDocuments/Docs/TransactionEvalConsumer_ServiceGraph_GapAnalysis.md) and move BL-050 / BL-054 to **Done** in [BACKLOG.md](../../../docs/BACKLOG.md).

**Pilot sign-off (2026-06-16):** Master validation §5 pass on `serviceId` `3756095a-e423-4aeb-b11a-fe6d3340fca5` · TE-GAP-01–08, TE-GAP-10 closed.
