# User Profile Service Graph Gap Issues (UP-GAP-01–08)

> **Pilot:** `platform-user-profile` · active `serviceId` **`eb407fc3-a58c-4a16-85cf-ede017652a9f`** (registry)  
> **Sign-off:** [UserProfileService_Pilot_SignOff.md](../../../../Downloads/DesignDocuments/Docs/UserProfileService_Pilot_SignOff.md)  
> **Gap analysis:** [UserProfileService_ServiceGraph_GapAnalysis.md](../../../../Downloads/DesignDocuments/Docs/UserProfileService_ServiceGraph_GapAnalysis.md)

---

## Closed

| ID | Backlog | Summary | Design |
|----|---------|---------|--------|
| UP-GAP-01 | BL-062 | `@*Mapping` constant path resolution | [UP-GAP-01 design](../TestSeer_UP_GAP01_AnnotationConstantPath_Design.md) |
| UP-GAP-08 | — | HTTP verbs on `@RequestMapping` | `JavaParserService.extractHttpMethodsFromRequestMapping` |
| UP-GAP-03 | BL-063 | Config gate attribution (`UseLegacyPagination`, `DisplayDuplicateDetectionDetails`) | [UP-GAP-03–07 design](../TestSeer_UP_GAP03_04_06_07_Implementation_Design.md) |
| UP-GAP-04 | BL-063 | Flow-diagram auto-anchor from `packagePrefix` | same |
| UP-GAP-06 | BL-063 | Data-access test filter + `storeType` inference | same |
| UP-GAP-07 | BL-063 | Maven `artifactId` → `module_path` for dependency-tree | same |
| UP-GAP-02 | BL-064 | Kafka producer outbound from `UserEmailAcceptanceRedeemEventProducer` | [UP-GAP-02 design](../TestSeer_UP_GAP02_Kafka_Redeem_Egress_Design.md) |
| UP-GAP-05 | BL-065 | Lombok `@AllArgsConstructor` controller class reachability | [UP-GAP-05 design](../TestSeer_UP_GAP05_Controller_Reachability_Design.md) |

---

## Open

_None — user-profile pilot gaps UP-GAP-01–08 closed (2026-06-17)._

---

## Validation (after re-index)

```bash
SVC=eb407fc3-a58c-4a16-85cf-ede017652a9f
BASE=http://localhost:8080
PKG=com.quotient.platform.userprofile

# UP-GAP-03
curl -s "$BASE/v1/facts/gates?orgId=quotient&serviceId=$SVC&packagePrefix=$PKG" \
  | jq '[.data[] | select(.gateKey|test("Pagination|Duplicate")) | {guardedSymbolFqn, gateKey}]'

# UP-GAP-04
curl -s "$BASE/v1/graph/flow-diagram?serviceId=$SVC&packagePrefix=$PKG" \
  | jq '.data.anchor.autoSelected'

# UP-GAP-06
curl -s "$BASE/v1/facts/data-access?serviceId=$SVC&packagePrefix=$PKG" \
  | jq '[.data[] | select(.handlerClassFqn|test("Test|IntTest"))] | length'

# UP-GAP-07
curl -s "$BASE/v1/graph/dependency-tree?serviceId=$SVC&modulePath=user-profile&hydrate=false" \
  | jq '.data.nodeIds | length'

# UP-GAP-02
curl -s "$BASE/v1/facts/outbound?orgId=quotient&serviceId=$SVC" \
  | jq '[.data[] | select(.sourceSymbol|test("UserEmailAcceptanceRedeemEventProducer"))] | length'

# UP-GAP-05
curl -s "$BASE/v1/graph/reachability?orgId=quotient&serviceId=$SVC&type=class&symbolFqn=com.quotient.platform.userprofile.web.api.UserHistoryApiController" \
  | jq '{nodeIds: (.data.nodeIds|length), edges: (.data.edges|length)}'
```
