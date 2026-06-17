# TestSeer BL-050 P0 — Implementation Design (all steps)

> **Status:** Ready for implementation  
> **Backlog:** [BL-050](../../docs/BACKLOG.md) · **Master roadmap (BL-050 Steps 1–8 + BL-054):** [TestSeer_BL050_BL054_Master_Implementation_Design.md](TestSeer_BL050_BL054_Master_Implementation_Design.md) · **Parent design:** [TestSeer_BL050_Kafka_Messaging_Graph_Design.md](TestSeer_BL050_Kafka_Messaging_Graph_Design.md)  
> **Issues registry:** [28-transaction-eval-graph-gap-issues.md](features/28-transaction-eval-graph-gap-issues.md)  
> **Pilot:** `transaction-eval-suite` · `serviceId` `81d1611f-e019-4ce4-94f1-7df64aa15c41` · commit `0bdc0be4`  
> **Author / date:** 2026-06-16

---

## 1. Executive summary

BL-050 P0 closes **four open gaps** on the transaction-eval pilot so TestSeer matches the manual service graph for **ingress reverse lookup**, **intra-service call graph**, **Kafka egress event-flow**, and **cross-repo pipeline trace**.

| Step | Issue | Req | Deliverable |
|------|-------|-----|-------------|
| **1** | TE-GAP-02 | KFK-04 | Reachability returns hydrated `nodes[]` + `edges[]` |
| **2** | TE-GAP-03 | TRG-13-R | `entry-flow/impact` accepts `Class.method` and `#method` |
| **3** | TE-GAP-01 | KFK-02, KFK-03 | Kafka publish hops X1–X5 in event-flow + `PUBLISHES_TO` |
| **4** | TE-GAP-04 | KFK-03, MSG-05 | Cross-repo pipeline subscriber + notification publisher |

**Already shipped (do not re-build):**

- `YamlKafkaTopicExtractor`, `KafkaListenerTriggerExtractor`, `KafkaSubscribeTriggerExtractor`
- `kafkaClassLinks` in `config/rule-packs/quotient-messaging.yml`
- `MessagingGraphProjector` `PUBLISHES_TO` / `SUBSCRIBES_TO` when `linked_class_fqn` set
- `GraphFactProjector` `INVOKES` / `ROUTES_TO` persistence (BL-053)
- `MessagingFlowService.buildSteps` includes PUBLISH rows when `linkedClassFqn` present

P0 work is mostly **query hydration**, **handler FQN parsing**, and **index/link verification** on the suite monorepo — not a new messaging subsystem.

---

## 2. Scope

### In scope (P0)

- TE-GAP-01, TE-GAP-02, TE-GAP-03, TE-GAP-04
- Pilot acceptance on `transaction-eval-suite` after re-index
- Unit + integration tests for each step
- Doc updates: OpenAPI description, [20-trg-13-reverse-impact.md](features/20-trg-13-reverse-impact.md), gap registry status

### Out of scope (P1+ — remain in parent BL-050)

- KFK-05 cron→handler (TE-GAP-05)
- KFK-06 external-endpoints (TE-GAP-06)
- KFK-07 facts/by-file (TE-GAP-07)
- KFK-08 package filter (TE-GAP-08)
- BL-054 flow-diagram composer (TE-GAP-10)
- **BL-055 / BL-057** cross-repo BFS scope + gap taxonomy — [design doc](TestSeer_BL055_BL057_CrossRepo_Trace_Hardening_Design.md) (TE-GAP-11, TE-GAP-13)

---

## 3. Baseline vs target (pilot)

| Surface | Baseline (2026-06-16 index) | P0 target |
|---------|----------------------------|-----------|
| Kafka ingress T1 | Linked `KAFKA_SUBSCRIBE` | Unchanged |
| Reverse impact | `triggers: []` for `Class.method` | ≥1 `KAFKA_SUBSCRIBE` |
| Reachability `type=class` | 121 `nodeIds`, `nodes: []` | `nodes ≥ 5`, `edges ≥ 4` |
| Event-flow egress X1–X5 | 0 steps per topic | Publisher hop per topic |
| Cross-repo pipeline | `NO_SUBSCRIBER` | Eval suite as subscriber when indexed |
| Notification topic | `NO_PUBLISHER` (possible) | Eval HTTP_PUBSUB publisher or explicit bundle gap |

---

## 4. Implementation steps

### Step 1 — TE-GAP-02: Reachability node/edge hydration (KFK-04)

**Status:** Done (2026-06-16). **Canonical design + test matrix:** [TestSeer_TE_GAP_02_Reachability_Hydration_Design.md](TestSeer_TE_GAP_02_Reachability_Hydration_Design.md).

**Priority:** First — unblocks BL-054 SFD-06 and agent call-graph queries.

#### Root cause (confirmed in code)

`GraphProjectionService` CTEs return reachable **node IDs** only. Every query method ends with:

```java
return new ReachabilityResult(ids, List.of());
```

`ReachabilityResult` has `nodeIds` and `nodes` but **no `edges` field**. `GraphFactProjector` **does** persist `INVOKES` edges to `graph_edges`; the gap is **query-layer hydration**, not missing index extraction.

#### Design

**4.1.1 Extend `ReachabilityResult`**

```java
public record ReachabilityResult(
    List<String> nodeIds,
    List<GraphNode> nodes,
    List<GraphEdgeView> edges   // NEW — serializable DTO, not persistence entity
) {}
```

Backward compatible: existing clients reading `nodeIds` unchanged; new clients use `nodes` / `edges`.

**4.1.2 Add `GraphSubgraphHydrator`**

Package: `io.testseer.backend.graph`

```java
public final class GraphSubgraphHydrator {
    Subgraph hydrateNodes(Collection<String> nodeIds);
    Subgraph hydrateSubgraph(String anchorNodeId, Collection<String> nodeIds, boolean includeEdges);
}
```

| Method | SQL |
|--------|-----|
| `loadNodes` | `SELECT * FROM graph_nodes WHERE id IN (:ids)` |
| `loadEdges` | `SELECT from_node, to_node, edge_type, confidence, evidence FROM graph_edges WHERE from_node IN (:ids) AND to_node IN (:ids) AND edge_type IN (...)` |

Edge types for class reachability: `INVOKES`, `ROUTES_TO`, `DEPENDS_ON`, `PUBLISHES_TO`, `SUBSCRIBES_TO`, `GUARDED_BY`.

**4.1.3 Wire into `GraphProjectionService`**

Replace bare `new ReachabilityResult(ids, List.of())` with:

```java
Subgraph sg = hydrator.hydrateSubgraph(startNodeId, ids, true);
return new ReachabilityResult(ids, sg.nodes(), sg.edges());
```

Apply to:

- `classDependsOnClassForward`
- `methodForward`
- `immediateNeighborhood` (optional P0 — high value for viz)
- **Not** `serviceCallsServiceForward` in P0 unless trivial (cross-service `CALLS` only)

**4.1.4 Include anchor node**

CTE returns **reachable targets**, not the start node. Hydration must **union** `startNodeId` into the ID set so the response includes the anchor class/method.

**4.1.5 `GraphQueryController`**

No new params. Document in OpenAPI:

- `type=class` + `symbolFqn` required for intra-service graph
- `type=service` + bare `serviceId` = cross-service `CALLS` only (GRP-12)

#### Files to change

| File | Change |
|------|--------|
| `graph/ReachabilityResult.java` | Add `edges` |
| `graph/GraphEdgeView.java` | **New** record for API |
| `graph/GraphSubgraphHydrator.java` | **New** |
| `graph/GraphProjectionService.java` | Hydrate all forward reachability methods used by REST |
| `query/GraphQueryController.java` | OpenAPI text only |
| `graph/GraphNodeRepository.java` | `findByIds(Collection<String>)` if missing |

#### Tests

| Test | Assert |
|------|--------|
| `GraphSubgraphHydratorTest` | Fixture nodes/edges → hydrated subgraph |
| `GraphQueryControllerReachabilityTest` | `type=class` returns non-empty `nodes` and `edges` |
| `TransactionEvalSuiteGraphIT` | Index fixture → consumer → `TransactionEvaluationService` edge exists | **Done** |

#### Validation (pilot)

```bash
curl -s "$BASE/v1/graph/reachability?orgId=$ORG&serviceId=$SVC&type=class\
&symbolFqn=com.quotient.platform.transaction.eval.consumer.TransactionEvalConsumer" \
  | jq '{nodeIds: (.data.nodeIds|length), nodes: (.data.nodes|length), edges: (.data.edges|length)}'
```

**Pass:** `nodes >= 5`, `edges >= 4`, one edge target contains `TransactionEvaluationService`.

---

### Step 2 — TE-GAP-03: Reverse impact handler FQN parsing (TRG-13-R)

**Priority:** Second — small, high-impact fix; unblocks SFD-18.

#### Root cause (confirmed in code)

`EntryFlowService.parseHandlerFqn` supports:

- `com.foo.Bar#onMessage` → class + method
- `com.foo.Bar` → class only

It does **not** support **`com.foo.Bar.onMessage`** (dot notation). Gap analysis validation curls and agents use dot form:

```
handlerFqn=...TransactionEvalConsumer.processSalesCanonicalEvent
```

That is parsed as a **single invalid class FQN**, so SQL `linked_handler_fqn = :handlerClass` matches zero rows.

This is **not** a missing `KAFKA_SUBSCRIBE` trigger — forward T1 proves the trigger exists.

#### Design

**4.2.1 Extend `parseHandlerFqn`**

After `#` split, if no `#` and string contains `.`:

```java
int lastDot = trimmed.lastIndexOf('.');
if (lastDot > 0) {
    String candidateMethod = trimmed.substring(lastDot + 1);
    String candidateClass = trimmed.substring(0, lastDot);
    if (isJavaMethodName(candidateMethod) && candidateClass.contains(".")) {
        return new ParsedHandler(candidateClass, candidateMethod, simpleName(candidateClass));
    }
}
```

`isJavaMethodName`: matches `^[a-z][a-zA-Z0-9_]*$` (standard Java method naming).

**Precedence:**

1. `#` separator (canonical — document in API)
2. Dot method suffix when method segment is lowercase-led
3. Class-only FQN

**4.2.2 Impact query tiers (unchanged logic, fixed input)**

| Tier | Predicate |
|------|-----------|
| EXACT + METHOD | `linked_handler_fqn = class` AND `linked_method = method` |
| EXACT | `linked_handler_fqn = class` (no method in input) |
| SIMPLE_NAME | org-wide suffix fallback |

**4.2.3 API documentation**

Update [20-trg-13-reverse-impact.md](features/20-trg-13-reverse-impact.md) handler table:

| Input | Class | Method |
|-------|-------|--------|
| `com.foo.Bar` | `com.foo.Bar` | — |
| `com.foo.Bar#onMessage` | `com.foo.Bar` | `onMessage` |
| `com.foo.Bar.onMessage` | `com.foo.Bar` | `onMessage` |

**4.2.4 MCP**

`testseer_get_entry_triggers` with `handlerFqn` — no code change if it delegates to same service.

#### Files to change

| File | Change |
|------|--------|
| `query/EntryFlowService.java` | `parseHandlerFqn`, `isJavaMethodName` |
| `query/EntryFlowServiceHandlerParsingTest.java` | Dot-notation cases + negative (class named `Foo.Bar` package) |
| `features/20-trg-13-reverse-impact.md` | Input formats |
| `test/.../EntryTriggerQueryControllerTest.java` | Impact integration with dot FQN |

#### Validation (pilot)

```bash
# Dot notation (agent-friendly)
curl -s "$BASE/v1/graph/entry-flow/impact?orgId=$ORG&serviceId=$SVC\
&handlerFqn=com.quotient.platform.transaction.eval.consumer.TransactionEvalConsumer.processSalesCanonicalEvent" \
  | jq '.data.triggers | length, .[0].triggerKind, .[0].trigger.pathPattern'

# Hash notation (canonical)
curl -s "$BASE/v1/graph/entry-flow/impact?orgId=$ORG&serviceId=$SVC\
&handlerFqn=com.quotient.platform.transaction.eval.consumer.TransactionEvalConsumer#processSalesCanonicalEvent" \
  | jq '.data.triggers | length'
```

**Pass:** `triggers | length >= 1`, `triggerKind == "KAFKA_SUBSCRIBE"`, `pathPattern` matches pipeline topic.

---

### Step 3 — TE-GAP-01: Kafka publish hops X1–X5 (KFK-02, KFK-03)

**Priority:** Third — depends on stable index path; rule pack already seeded.

#### Root cause (layered)

| Layer | Issue |
|-------|--------|
| Index | `YamlKafkaTopicExtractor` may emit PUBLISH facts but `linked_class_fqn` null when `module` on fact ≠ `transaction-eval-consumer` in suite layout |
| Index | Suite yaml for redeem/reward producers may live under `evaluation-domain/*` modules — rule pack `module: transaction-eval-consumer` filter skips match |
| Index | `DualWriteService` must persist kafka facts with `attributes.transport=KAFKA` |
| Graph | `MessagingGraphProjector` only edges when `linkedClassFqn != null` |
| Query | `event-flow?shortId=` filters `pubsub_resource_facts` — empty if no rows for topic+env |

#### Design

**4.3.1 Rule pack module relaxation**

In `MessagingClassLinker.matchKafkaRulePack`, when `rule.module()` is set but `fact.moduleName()` differs:

- **Tier A:** exact module match (0.99) — keep
- **Tier B (new):** if `rule.topicShortId` + `rule.role` match and `rule.classFqn` exists in indexed Java files for this service, apply link at 0.97 **ignoring module** (monorepo suite)

Alternative: add duplicate rule pack rows per suite module (`evaluation-domain-common`, etc.) — prefer Tier B to avoid rule duplication.

**4.3.2 Verify yaml PUBLISH facts for all five topics**

Pilot topic map (from manual graph + rule pack):

| Manual | Topic shortId | Linked class | Method |
|--------|---------------|--------------|--------|
| X1 | `QUOT.SALES.TRANSACTION.PROCESSED.EVENTS` | `StxnProcessedEventProducer` | `publishEvent` |
| X2 | `QUOT.REBATE.REDEEM.EVENTS` | `TransactionHelper` | `postRedeemAndPayoutEvent` |
| X3 | `QUOT.REBATE.REWARD-STATUS.EVENTS` | `TransactionHelper` | `postRewardNotifications` |
| X4 | `QUOT.FRAUD.RULES.EVALUATION.EVENTS` | `FraudRulesEvaluationEventProducer` | `postFraudRulesEvaluationEvent` |
| X5 | `QUOT.FRAUD.TRANSACTION.PATTERN.EVENTS` | `PatternCheckEventProducer` | `postPatternCheckEvent` |

**Index verification query (post re-index):**

```sql
SELECT short_id, role, linked_class_fqn, module_name, attributes->>'transport'
FROM pubsub_resource_facts
WHERE service_id = :svc AND role = 'PUBLISH'
  AND attributes->>'transport' = 'KAFKA';
```

Expect **5 rows** with non-null `linked_class_fqn`.

**4.3.3 `YamlKafkaTopicExtractor` hardening**

- Ensure `rolesForPrefix` emits `PUBLISH` when only `producer` block exists (no `producer.enabled` in some Quotient yamls — default PUBLISH if topic under non-pipeline segment).
- Resolve `topicName` camelCase keys (already supported).
- Set `resourceKind=TOPIC` for all Kafka facts.

**4.3.4 Producer heuristic fallback**

If rule pack + module match fail, extend `isKafkaProducer`:

- Class name `*EventProducer`
- Or class contains `AsyncProducer` field + method `publishEvent` / `post*Event`

Map to topic via `contentMentionsTopic` or qualifier→spring key map in rule pack (`kafkaProducerQualifiers` — optional P0 add).

**4.3.5 Event-flow single-service**

`MessagingFlowService.trace(serviceId, shortId, env)` already builds steps from `queryPubSub`. P0 fix is **data**, not `buildSteps` logic.

Add gap code when topic exists but `linkedClassFqn` null:

```java
gaps.add(new FlowGap("UNLINKED_KAFKA_PUBLISHER", shortId + " has no linked producer class"));
```

**4.3.6 Graph edges**

Confirm `MessagingGraphProjector.project` runs **after** linker in `IndexingOrchestrator` pipeline. Each linked PUBLISH fact → `PUBLISHES_TO` class→TOPIC.

#### Files to change

| File | Change |
|------|--------|
| `ingestion/messaging/MessagingClassLinker.java` | Monorepo module relaxation (Tier B) |
| `ingestion/messaging/YamlKafkaTopicExtractor.java` | PUBLISH role defaults for producer-only yaml |
| `query/MessagingFlowService.java` | `UNLINKED_KAFKA_PUBLISHER` gap |
| `config/rule-packs/quotient-messaging.yml` | Only if Tier B insufficient — add module aliases |
| `ingestion/DualWriteService.java` | Verify kafka facts persisted (read-only audit) |

#### Tests

| Test | Assert |
|------|--------|
| `MessagingClassLinkerKafkaPublishTest` | Rule pack links all 5 topics on suite fixture |
| `YamlKafkaTopicExtractorTest` | Producer-only yaml → PUBLISH fact |
| `MessagingFlowServiceKafkaIT` | `event-flow?shortId=PROCESSED` → ≥1 step with outbound |

#### Validation (pilot)

```bash
TOPICS=(
  QUOT.SALES.TRANSACTION.PROCESSED.EVENTS
  QUOT.REBATE.REDEEM.EVENTS
  QUOT.REBATE.REWARD-STATUS.EVENTS
  QUOT.FRAUD.RULES.EVALUATION.EVENTS
  QUOT.FRAUD.TRANSACTION.PATTERN.EVENTS
)
for T in "${TOPICS[@]}"; do
  echo "=== $T ==="
  curl -s "$BASE/v1/graph/event-flow?orgId=$ORG&serviceId=$SVC&shortId=$T&env=dev" \
    | jq '[.data.steps[].outbounds[]? | select(.role=="PUBLISH")] | length'
done
```

**Pass:** Each topic ≥1 PUBLISH outbound; `linkedClassFqn` on step handler matches rule pack.

---

### Step 4 — TE-GAP-04: Cross-repo pipeline & notification (KFK-03, MSG-05)

**Priority:** Fourth — requires org bundle index + Steps 1–3 data.

#### Root cause

| Gap | Cause |
|-----|--------|
| Pipeline `NO_SUBSCRIBER` | Upstream **publisher** repo not in `quotient-full` bundle at index time, **or** publisher uses different topic shortId (env alias) |
| Pipeline `NO_PUBLISHER` | Same — sales-trans publisher not indexed |
| Notification `NO_PUBLISHER` | `HttpPubSubPublishLinker` virtual facts not present until re-index; or env lane mismatch on `DEV_T.NOTIFICATION_REQ` |
| Kafka join | `traceCrossRepo` uses `pubsub_resource_facts` org-wide — **should** include Kafka if indexed; verify `transport` on org rows |

#### Design

**4.4.1 Bundle index prerequisite**

Document operational step (not code): before validation, run `index-all-repos.sh` with `quotient-full` including at minimum:

- `platform-transaction-eval-consumer` (suite)
- Upstream sales transaction **publisher** service (identify from manual graph — typically sales-trans pipeline producer repo in `workspace.yml`)

Add to `detectMissingBundleRepos` output — already surfaces missing repos.

**4.4.2 Topic alias join (P0 minimal)**

Extend `groupByShortId` / BFS queue seed to resolve aliases from rule pack:

```yaml
# quotient-messaging.yml (existing httpPubSubPublishLinks.topicAliases pattern)
kafkaTopicAliases:
  - logical: QUOT.SALES.TRANSACTION.PIPELINE.EVENTS
    aliases: [PDN_T.SALES_TRANSACTION_PIPELINE]  # verify from prod yaml before commit
```

`traceCrossRepo` normalizes `startTopic` and indexed `short_id` through alias map before join.

**4.4.3 Gap quality**

Distinguish in `FlowGap`:

| Code | Meaning |
|------|---------|
| `NO_SUBSCRIBER` | No indexed subscriber — **true orphan** |
| `NO_SUBSCRIBER_INDEX_GAP` | Subscriber repo listed in bundle manifest but not indexed |
| `NO_PUBLISHER` | Same split |

Use `missingForBundle` on `CrossRepoFlowReport` to downgrade false orphans.

**4.4.4 Notification hop (BL-051 verify)**

1. Re-index eval suite after Step 3.
2. Confirm virtual row:

```sql
SELECT short_id, role, linked_class_fqn, attributes
FROM pubsub_resource_facts
WHERE service_id = :svc AND short_id LIKE '%NOTIFICATION_REQ%';
```

3. `HttpPubSubPublishLinker` should emit `transport=HTTP_PUBSUB`, `role=PUBLISH`.

If missing: audit linker invocation in `MessagingFactOrchestrator` for `rest.apis.pubsub` yaml in consumer config-map.

**4.4.5 Cross-repo transport**

`MessagingFlowService` L131: when all participants are `KAFKA`, set `transport=KAFKA` on hop (avoid default PUBSUB).

#### Files to change

| File | Change |
|------|--------|
| `query/MessagingFlowService.java` | Alias normalization; transport resolution; gap codes |
| `config/MessagingRulePack.java` | `kafkaTopicAliases` record |
| `config/rule-packs/quotient-messaging.yml` | Pipeline alias entries (after yaml verify) |
| `ingestion/messaging/HttpPubSubPublishLinker.java` | Audit only — fix if not invoked |

#### Tests

| Test | Assert |
|------|--------|
| `MessagingFlowServiceCrossRepoKafkaTest` | Subscriber + publisher on same logical topic across two service IDs |
| `MessagingFlowServiceTopicAliasTest` | Alias maps PDN name → QUOT name |

#### Validation (pilot)

```bash
curl -s "$BASE/v1/graph/event-flow/cross-repo?orgId=$ORG&env=dev&bundle=quotient-full\
&shortId=QUOT.SALES.TRANSACTION.PIPELINE.EVENTS" \
  | jq '{hops: (.data.hops|length), gaps: .data.gaps, subscribers: [.data.hops[].subscribers[].serviceName]}'

curl -s "$BASE/v1/graph/event-flow/cross-repo?orgId=$ORG&env=dev&bundle=quotient-full\
&shortId=DEV_T.NOTIFICATION_REQ" \
  | jq '[.data.hops[].publishers[] | select(.transport=="HTTP_PUBSUB")] | length'
```

**Pass:** Pipeline hop lists transaction-eval subscriber; notification has HTTP_PUBSUB publisher from eval **or** `missingForBundle` explains absent publisher repo.

---

## 5. PR breakdown (recommended)

| PR | Step | Title | Est. |
|----|------|-------|------|
| **PR-1** | 1 | `feat(graph): hydrate reachability nodes and edges` | 1–2 d |
| **PR-2** | 2 | `fix(entry-flow): parse dot-notation handlerFqn for impact` | 0.5 d |
| **PR-3** | 3 | `fix(kafka): link publish topics on suite monorepo index` | 2–3 d |
| **PR-4** | 4 | `fix(messaging): cross-repo Kafka aliases and gap codes` | 1–2 d |

Merge order: PR-1 → PR-2 → PR-3 → PR-4. PR-2 can ship in parallel with PR-1.

After **PR-4**: single pilot re-index + run [closure checklist](features/28-transaction-eval-graph-gap-issues.md#closure-checklist-pilot-sign-off).

---

## 6. Index pipeline order (verify unchanged)

```mermaid
sequenceDiagram
  participant Orch as IndexingOrchestrator
  participant Yaml as YamlKafkaTopicExtractor
  participant Link as MessagingClassLinker
  participant MGP as MessagingGraphProjector
  participant GFP as GraphFactProjector
  participant DW as DualWriteService

  Orch->>Yaml: extract kafka topics
  Orch->>Link: linkPubSub(pubsub+kafka)
  Orch->>DW: pubsub_resource_facts
  Orch->>MGP: PUBLISHES_TO / SUBSCRIBES_TO
  Orch->>GFP: INVOKES / ROUTES_TO
```

If P0 validation fails after code merge, trace **which stage** loses `linked_class_fqn` using SQL above before adding new extractors.

---

## 7. Acceptance criteria (P0 sign-off)

| ID | Criterion | Step |
|----|-----------|------|
| P0-AC-1 | Reachability `type=class` → `nodes`/`edges` non-empty | 1 |
| P0-AC-2 | Impact dot + hash `handlerFqn` → Kafka trigger | 2 |
| P0-AC-3 | Five egress topics each have event-flow PUBLISH outbound | 3 |
| P0-AC-4 | Pipeline cross-repo shows eval subscriber | 4 |
| P0-AC-5 | Notification cross-repo shows HTTP_PUBSUB publisher or documented bundle gap | 4 |
| P0-AC-6 | Pub/Sub regression: `affiliate-notifications` index unchanged | all |

---

## 8. Pilot validation script

```bash
#!/usr/bin/env bash
set -euo pipefail
ORG=quotient
BASE=http://localhost:8080
REPO=/Users/mrinalthigale/Documents/GitHub/platform-transaction-eval-consumer

# Re-index — capture new serviceId
INDEX=$(curl -s -X POST "$BASE/admin/index/local" \
  -H 'Content-Type: application/json' \
  -d "{\"orgId\":\"$ORG\",\"path\":\"$REPO\"}")
SVC=$(echo "$INDEX" | jq -r '.data.serviceId // .serviceId')
echo "serviceId=$SVC"

HANDLER=com.quotient.platform.transaction.eval.consumer.TransactionEvalConsumer

echo "=== P0-AC-1 reachability ==="
curl -s "$BASE/v1/graph/reachability?orgId=$ORG&serviceId=$SVC&type=class&symbolFqn=$HANDLER" \
  | jq '{nodes: (.data.nodes|length), edges: (.data.edges|length)}'

echo "=== P0-AC-2 impact ==="
curl -s "$BASE/v1/graph/entry-flow/impact?orgId=$ORG&serviceId=$SVC\
&handlerFqn=${HANDLER}.processSalesCanonicalEvent" | jq '.data.triggers|length'

echo "=== P0-AC-3 egress topics ==="
for T in QUOT.SALES.TRANSACTION.PROCESSED.EVENTS QUOT.REBATE.REDEEM.EVENTS \
         QUOT.REBATE.REWARD-STATUS.EVENTS QUOT.FRAUD.RULES.EVALUATION.EVENTS \
         QUOT.FRAUD.TRANSACTION.PATTERN.EVENTS; do
  N=$(curl -s "$BASE/v1/graph/event-flow?orgId=$ORG&serviceId=$SVC&shortId=$T&env=dev" \
    | jq '[.data.steps[].outbounds[]?]|length')
  echo "$T outbounds=$N"
done

echo "=== P0-AC-4/5 cross-repo ==="
curl -s "$BASE/v1/graph/event-flow/cross-repo?orgId=$ORG&env=dev&bundle=quotient-full\
&shortId=QUOT.SALES.TRANSACTION.PIPELINE.EVENTS" | jq '.data.gaps'
curl -s "$BASE/v1/graph/event-flow/cross-repo?orgId=$ORG&env=dev&bundle=quotient-full\
&shortId=DEV_T.NOTIFICATION_REQ" | jq '.data.gaps'

echo "=== cross-repo narrative (human-readable) ==="
curl -s "$BASE/v1/graph/event-flow/cross-repo?orgId=$ORG&env=dev&bundle=quotient-full\
&shortId=QUOT.SALES.TRANSACTION.PIPELINE.EVENTS&maxHops=12" | jq -r '.data.narrative[]'
curl -s "$BASE/v1/graph/event-flow/cross-repo?orgId=$ORG&env=dev&bundle=quotient-full\
&shortId=DEV_T.NOTIFICATION_REQ&maxHops=12" | jq '.data.hopSummaries[] | {order, summaryLine}'

echo "=== BL-056 hop dedupe (notification hop 1) ==="
curl -s "$BASE/v1/graph/event-flow/cross-repo?orgId=$ORG&env=dev&bundle=quotient-full\
&shortId=DEV_T.NOTIFICATION_REQ&maxHops=1" \
  | jq '.data.hops[0] | {pubCount: (.publishers|length), pubServices: ([.publishers[].serviceName] | unique)}'

# Post BL-055/057 — full cross-repo hardening script:
# testseer-backend/docs/TestSeer_BL055_BL057_CrossRepo_Trace_Hardening_Design.md §9
```

---

## 9. Risks & mitigations

| Risk | Mitigation |
|------|------------|
| Dot FQN splits inner class wrong | Only split when method segment matches `^[a-z]` |
| Hydration N+1 on large graphs | Batch `IN` query; cap edge load at 500 rows per request |
| Suite module names break rule pack | Tier B module relaxation |
| Upstream publisher repo unknown | Document in `workspace.yml` bundle; manual graph names actor |
| Topic alias wrong for PDN | Verify from prod yaml before rule pack commit |

---

## 10. References

| Doc | Purpose |
|-----|---------|
| [TestSeer_BL050_Kafka_Messaging_Graph_Design.md](TestSeer_BL050_Kafka_Messaging_Graph_Design.md) | Full BL-050 (KFK-01–08) |
| [28-transaction-eval-graph-gap-issues.md](features/28-transaction-eval-graph-gap-issues.md) | Per-issue validation |
| [TestSeer_BL053_Processor_Routing_CallGraph_Design.md](TestSeer_BL053_Processor_Routing_CallGraph_Design.md) | INVOKES / routing (shipped) |
| [TestSeer_HTTP_PubSub_EventFlow_Hop_Design.md](TestSeer_HTTP_PubSub_EventFlow_Hop_Design.md) | BL-051 notification |
| Gap analysis (external) | `DesignDocuments/Docs/TransactionEvalConsumer_ServiceGraph_GapAnalysis.md` |

---

## 11. Sign-off

- [ ] Steps 1–4 merged
- [ ] Pilot script all P0-AC pass
- [ ] TE-GAP-01–04 marked closed in issue registry
- [ ] BACKLOG BL-050 note updated (P0 done; P1 KFK-05–08 remain)
- [ ] CHANGELOG entry if API adds `edges` to reachability
