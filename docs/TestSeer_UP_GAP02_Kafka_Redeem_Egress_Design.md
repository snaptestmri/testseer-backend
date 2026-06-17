# TestSeer UP-GAP-02 — Kafka Redeem Egress (`UserEmailAcceptanceRedeemEventProducer`)

> **Status:** Implemented (2026-06-17)  
> **Backlog:** **BL-064**  
> **Pilot:** `platform-user-profile` · `serviceId` `bd1b4101-ac6f-4b57-92ce-a3371ecc4ffd`  
> **Gap analysis:** [UserProfileService_ServiceGraph_GapAnalysis.md](../../../../Downloads/DesignDocuments/Docs/UserProfileService_ServiceGraph_GapAnalysis.md)

---

## 1. Executive summary

| Gap | Symptom | Fix |
|-----|---------|-----|
| **UP-GAP-02** | `facts/outbound` has no row for `UserEmailAcceptanceRedeemEventProducer#publishEvent` → `QUOT.REBATE.REDEEM.EVENTS`; event-flow shows `UNLINKED_KAFKA_PUBLISHER` | `KafkaPublishOutboundExtractor` + existing `MessagingClassLinker` / `YamlKafkaTopicExtractor` wiring in `MessagingFactOrchestrator` |

**Production code unchanged** — indexing-only fix in TestSeer.

---

## 2. Production pattern (user-profile)

```java
@Component
@AllArgsConstructor(onConstructor = @__({@Autowired}))
public class UserEmailAcceptanceRedeemEventProducer {

    @Qualifier("rebateRedeemSyncProducer")
    private final SyncProducer<MessageEnvelope.QMsgEvent> rebateRedeemSyncProducer;

    public void publishEvent(...) {
        rebateRedeemSyncProducer.send(qMsgEvent);
    }
}
```

**K8s config** (`kubernetes-manifests/*/user-profile-service.*.config-map.yaml`):

```yaml
kafka:
  topics:
    rebate:
      redeem:
        topic-name: QUOT.REBATE.REDEEM.EVENTS
        producer:
          enabled: true
```

Flattened spring key: `kafka.topics.rebate.redeem.topic-name`.

---

## 3. Root cause

`JavaParserService.extractOutboundCalls` only captures HTTP client calls (RestTemplate, WebClient, Feign).  
`SyncProducer.send(...)` is a **Kafka publish** — it was never emitted as `outbound_call_facts`.

`MessagingClassLinker` already infers Kafka producers (`*EventProducer` + `SyncProducer` in source), but without outbound facts the service-graph pilot validation and some graph projections lacked an explicit egress edge.

---

## 4. Implementation

### 4.1 `KafkaPublishOutboundExtractor` (new)

| Step | Logic |
|------|--------|
| Index | `FieldInjectionDef` where `declaredType` contains `SyncProducer` / `AsyncProducer` / `KafkaTemplate` |
| Scan | `methodCalls` where `calleeMethod == "send"` and `calleeVariable` matches a producer field |
| Topic resolve | (1) linked `PubSubResourceFact` with `linkedClassFqn == publisher class`; (2) `@Qualifier` / bean name → `kafka.topics.{segment}` prefix match on yaml facts |
| Emit | `OutboundCallFact(sourceSymbol, "KAFKA", topicShortId, "KAFKA_PUBLISH", confidence)` |

**Bean → spring segment:** `rebateRedeemSyncProducer` → strip `SyncProducer` suffix → `rebateRedeem` → `rebate.redeem`.

### 4.2 Orchestrator wire (`MessagingFactOrchestrator`)

```text
javaparserOutbound = extractOutboundCallFacts(models)
kafkaOutbound      = kafkaPublishOutboundExtractor.extract(models, linkedPubSub)
outboundFacts      = crossModuleOutboundAttributor.attributeToCallers(
                         models, mergeOutboundFacts(javaparserOutbound, kafkaOutbound))
```

### 4.3 Supporting extractors (existing, unchanged)

| Component | Role |
|-----------|------|
| `MethodCallGraphExtractor.extractFieldInjections` | Field + constructor `@Qualifier` on producer beans |
| `YamlKafkaTopicExtractor` | `kafka.topics.*.topic-name` from k8s ConfigMap yaml |
| `MessagingClassLinker.isKafkaProducer` | Links `QUOT.REBATE.REDEEM.EVENTS` → `UserEmailAcceptanceRedeemEventProducer#publishEvent` |

---

## 5. Validation

```bash
SVC=bd1b4101-ac6f-4b57-92ce-a3371ecc4ffd
BASE=http://localhost:8080
PKG=com.quotient.platform.userprofile

# UP-GAP-02 — outbound fact
curl -s "$BASE/v1/facts/outbound?orgId=quotient&serviceId=$SVC" \
  | jq '[.data[] | select(.sourceSymbol|test("UserEmailAcceptanceRedeemEventProducer"))]'

# Event-flow — linked publisher (no UNLINKED_KAFKA_PUBLISHER for redeem topic)
curl -s "$BASE/v1/graph/event-flow?serviceId=$SVC&packagePrefix=$PKG" \
  | jq '[.data.gaps[] | select(.code=="UNLINKED_KAFKA_PUBLISHER")] | length'
```

**Pass:**

- ≥1 outbound row: `httpMethod=KAFKA`, `path=QUOT.REBATE.REDEEM.EVENTS`, `sourceSymbol` ends with `#publishEvent`
- `UNLINKED_KAFKA_PUBLISHER` count 0 for redeem topic after re-index

### Re-index

```bash
curl -X DELETE "$BASE/admin/index/$SVC"
curl -s -X POST "$BASE/admin/index/local" -H 'Content-Type: application/json' \
  -d '{"orgId":"quotient","path":"/path/to/platform-user-profile","serviceId":"'$SVC'"}'
```

---

## 6. Tests

| Test | Coverage |
|------|----------|
| `KafkaPublishOutboundExtractorTest` | Bean segment mapping; synthetic model + yaml topic |
| `MessagingClassLinkerTest.linkPubSub_linksSyncProducerKafkaPublisher` | Pub/sub → producer class link |
| `KafkaUserProfileRealSourceIT` | Real `UserEmailAcceptanceRedeemEventProducer.java` + dev ConfigMap end-to-end |

---

## 7. Files touched

| File | Change |
|------|--------|
| `ingestion/messaging/KafkaPublishOutboundExtractor.java` | **new** |
| `ingestion/messaging/MessagingFactOrchestrator.java` | merge kafka outbound into batch |
| `ingestion/graph/MethodCallGraphExtractor.java` | producer field injection + `isKafkaProducerType` |
| `test/.../KafkaPublishOutboundExtractorTest.java` | **new** |
| `test/.../KafkaUserProfileRealSourceIT.java` | **new** |
| `docs/features/31-user-profile-graph-gap-issues.md` | close UP-GAP-02 |
| `docs/BACKLOG.md` | BL-064 done |
