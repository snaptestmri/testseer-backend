# TestSeer UP-GAP-05 — Controller class reachability (Lombok DI)

> **Status:** Implemented (2026-06-17)  
> **Backlog:** **BL-065**  
> **Pilot:** `platform-user-profile` · `UserHistoryApiController`  
> **Gap analysis:** [UserProfileService_ServiceGraph_GapAnalysis.md](../../../../Downloads/DesignDocuments/Docs/UserProfileService_ServiceGraph_GapAnalysis.md)

---

## 1. Executive summary

| Gap | Symptom | Fix |
|-----|---------|-----|
| **UP-GAP-05** | `GET /v1/graph/reachability?type=class&symbolFqn=...UserHistoryApiController` returns `nodeIds=0`, `edges=0` | Index Lombok `@AllArgsConstructor` / `@RequiredArgsConstructor` instance fields as injection candidates in `MethodCallGraphExtractor` |

Unlike receipt-service (interface + `IMPLEMENTS` bridge), user-profile controllers are **direct `@RestController`** classes with **Lombok constructor injection** — `@Autowired` appears only on the generated constructor, not on fields.

---

## 2. Production pattern (user-profile)

```java
@RestController
@AllArgsConstructor(onConstructor = @__({@Autowired}))
public class UserHistoryApiController {
    private final ShoppingHistoryServiceImpl shoppingHistoryServiceImpl;
    private ShoppingHistoryHelper shoppingHistoryHelper;

    public ResponseEntity<...> getUserProfileShoppingHistory(...) {
        shoppingHistoryHelper.validateAndGetShoppingHistoryContext(...);
        shoppingHistoryServiceImpl.getShoppingHistoryForUser(...);
    }
}
```

**Expected graph:** `UserHistoryApiController` —`INVOKES`/`DEPENDS_ON`→ `ShoppingHistoryServiceImpl`, `ShoppingHistoryHelper`, …

---

## 3. Root cause

`MethodCallGraphExtractor.extractFieldInjections` only indexes fields with `@Autowired` / `@Resource` / `@Inject` / `@Qualifier`, or Kafka producer types.

Lombok `@AllArgsConstructor` does not annotate fields — so:

1. `fieldInjections` is empty on the controller
2. `methodCalls` cannot resolve `shoppingHistoryServiceImpl` / `shoppingHistoryHelper` variable types
3. `GraphFactProjector` emits no `DEPENDS_ON` / `INVOKES` edges from the controller class node

---

## 4. Implementation

### 4.1 `MethodCallGraphExtractor.extractFieldInjections`

When the class declares `@AllArgsConstructor` or `@RequiredArgsConstructor`:

| Annotation | Fields indexed |
|------------|----------------|
| `@AllArgsConstructor` | All non-static instance fields |
| `@RequiredArgsConstructor` | `final` instance fields only |

Injection source tag: `LOMBOK_CONSTRUCTOR`. Existing explicitly-annotated fields are unchanged (deduped by variable+type key).

### 4.2 Downstream (unchanged wiring)

| Component | Role |
|-----------|------|
| `JavaParserService` | Calls `extractFieldInjections` → `methodCalls` |
| `GraphFactProjector.projectModel` | `DEPENDS_ON` from injections; `INVOKES` from `methodCalls` + field-use heuristic |
| `GraphProjectionService.classDependsOnClassForward` | CTE over `DEPENDS_ON`, `INVOKES`, `ROUTES_TO`, `IMPLEMENTS` |

---

## 5. Validation

```bash
SVC=<platform-user-profile serviceId>
BASE=http://localhost:8080
CTRL=com.quotient.platform.userprofile.web.api.UserHistoryApiController

curl -s "$BASE/v1/graph/reachability?orgId=quotient&serviceId=$SVC&type=class&symbolFqn=$CTRL" \
  | jq '{nodeIds: (.data.nodeIds|length), edges: (.data.edges|length),
         services: [.data.nodes[]?.symbolFqn|select(test("ShoppingHistory|TransactionHistory"))]}'
```

**Pass:** `nodeIds >= 2`, `edges >= 2`, reachable FQNs include `ShoppingHistoryServiceImpl` or `TransactionHistoryServiceImpl`.

Re-index after deploy: `DELETE /admin/index/{serviceId}` then `POST /admin/index/local`.

---

## 6. Tests

| Test | Coverage |
|------|----------|
| `MethodCallGraphExtractorTest` | Lombok field indexing; method-call resolution on synthetic controller |
| `UserProfileGraphFixture` + `UserProfileServiceGraphIT` | `GraphFactProjector` + reachability + REST envelope for Lombok controller chain |

---

## 7. Files touched

| File | Change |
|------|--------|
| `ingestion/graph/MethodCallGraphExtractor.java` | Lombok DI field indexing |
| `test/.../MethodCallGraphExtractorTest.java` | **new** |
| `test/.../graph/UserProfileGraphFixture.java` | **new** |
| `test/.../graph/UserProfileServiceGraphIT.java` | **new** |
| `docs/features/31-user-profile-graph-gap-issues.md` | close UP-GAP-05 |
| `docs/BACKLOG.md` | BL-065 done |
