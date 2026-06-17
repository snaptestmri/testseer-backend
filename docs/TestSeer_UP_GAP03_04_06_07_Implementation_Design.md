# TestSeer UP-GAP-03/04/06/07 — User Profile Graph Hardening

> **Status:** Implemented (2026-06-17) · **live-validated**  
> **Backlog:** **BL-063**  
> **Pilot:** `platform-user-profile` · active `serviceId` `eb407fc3-a58c-4a16-85cf-ede017652a9f`  
> **Sign-off:** [UserProfileService_Pilot_SignOff.md](../../../../Downloads/DesignDocuments/Docs/UserProfileService_Pilot_SignOff.md)

---

## 1. Executive summary

| Gap | Symptom | Fix |
|-----|---------|-----|
| **UP-GAP-03** | `facts/gates?packagePrefix=…userprofile` returns 0 — `UseLegacyPagination` / `DisplayDuplicateDetectionDetails` not indexed | Extend `FlowGateExtractor` for static-import `SystemConfigKeys` and `String.valueOf(SystemConfigKeys.X)` |
| **UP-GAP-04** | `flow-diagram?packagePrefix=…` without `anchor` fails | Auto-pick first `REST_INBOUND` trigger in package; surface `autoSelected` on anchor |
| **UP-GAP-06** | Data-access polluted by `*Test`/`*IntTest` handlers; `storeType` null on Cassandra/BQ touches | `HandlerScopeFilter` at index + query; `StoreType.fromPackageHint` on accessor FQN |
| **UP-GAP-07** | `dependency-tree?modulePath=user-profile` empty — stored path is `""` | `MavenModuleLookupService` resolves artifactId → `module_path` |

---

## 2. UP-GAP-03 — Config gate extraction

### Production patterns (user-profile)

```java
// RestHelper — static import
import static com.quotient.platform.domain.SystemConfigKeys.UseLegacyPagination;
configService.isConfigEnabled(partnerId, UseLegacyPagination.toString());

// TransactionHistoryServiceImpl
configService.isConfigEnabled(String.valueOf(SystemConfigKeys.DisplayDuplicateDetectionDetails));
```

Existing patterns only match `SystemConfigKeys.X.name()` / `.toString()` inline.

### Implementation

| Component | Change |
|-----------|--------|
| `FlowGateExtractor` | Parse `import static …SystemConfigKeys.X`; partner `isConfigEnabled(…, X.toString())` when `X` is statically imported |
| `FlowGateExtractor` | `isConfigEnabled(String.valueOf(SystemConfigKeys.X))` |
| `facts/gates` | Existing `packagePrefix` SQL filter (BL-061) — no query change |

### Validation

```bash
curl -s "$BASE/v1/facts/gates?orgId=quotient&serviceId=$SVC&packagePrefix=com.quotient.platform.userprofile" \
  | jq '[.data[] | select(.gateKey|test("Pagination|Duplicate")) | {guardedSymbolFqn, gateKey}]'
```

**Pass:** ≥2 gates on `RestHelper` / `TransactionHistoryServiceImpl`.

---

## 3. UP-GAP-04 — Flow diagram auto-anchor

### Behavior

| Request | Result |
|---------|--------|
| `anchor` provided | Unchanged (BL-054) |
| `anchor` omitted + `packagePrefix` set | First `REST_INBOUND` trigger matching prefix → `triggerId:{id}` |
| Neither | `400` with message requiring `anchor` or `packagePrefix` |

`FlowDiagramAnchor.autoSelected=true` when inferred.

### Validation

```bash
curl -s "$BASE/v1/graph/flow-diagram?serviceId=$SVC&packagePrefix=com.quotient.platform.userprofile" \
  | jq '{anchor: .data.anchor, nodes: .data.stats.nodeCount}'
```

**Pass:** `anchor.autoSelected == true`, `nodeCount > 0`.

---

## 4. UP-GAP-06 — Data-access scope and store type

### Index time

- Skip `src/test/java` paths for data-access extractors (`HandlerAccessLinker`, `MongoAccessExtractor`, `BigQueryDirectExtractor`, fallback `DataAccessExtractor`).

### Query time

- `GET /v1/facts/data-access?excludeTestHandlers=true` (default) filters `*Test`, `*IT`, `*IntTest` handler FQNs.
- Optional `packagePrefix` filter.

### Store type

- `HandlerAccessLinker.inferStoreType` delegates to `StoreType.fromPackageHint(accessorFqn)` (`.data.nosql.` → `CASSANDRA`, etc.).
- `DataAccessExtractor` infers store from repo field name heuristics when catalog linker absent.

### Validation

```bash
curl -s "$BASE/v1/facts/data-access?serviceId=$SVC&packagePrefix=com.quotient.platform.userprofile" \
  | jq '[.data[] | select(.handlerClassFqn|test("Test|IntTest"))] | length'
# Pass: 0

curl -s "$BASE/v1/facts/data-access?serviceId=$SVC" \
  | jq '[.data[] | select(.storeType == null)] | length'
# Pass: 0 or only non-DAO library stubs
```

---

## 5. UP-GAP-07 — Single-module Maven tree

Root `pom.xml` → `module_path = ""`, `artifact_id = user-profile`. Pilots query `modulePath=user-profile`.

### Implementation

`MavenModuleLookupService.resolveModulePath(serviceId, commitSha, hint)`:

1. Exact `module_path` match  
2. `artifact_id = hint`  
3. Single indexed module → that module  
4. Default root selection (jar packaging preference)

Used by `DependencyTreeGraphService` and `MavenDependencyQueryService`.

### Validation

```bash
curl -s "$BASE/v1/graph/dependency-tree?serviceId=$SVC&modulePath=user-profile&hydrate=false" \
  | jq '.data | {rootModulePath, nodeCount: (.nodeIds|length)}'
```

**Pass:** `rootModulePath` is `""` or resolves; `nodeIds` length > 0.

---

## 7. Live validation (2026-06-17)

`serviceId=eb407fc3-a58c-4a16-85cf-ede017652a9f`, index `CURRENT` @ `bb561b32`.

| Gap | Check | Result |
|-----|-------|--------|
| UP-GAP-03 | `guardedSymbolFqn` on Pagination/Duplicate gates | **Pass** — `RestHelper`, `TransactionHistoryServiceImpl` |
| UP-GAP-04 | `flow-diagram` auto-anchor | **Pass** — 21 nodes |
| UP-GAP-06 | test handlers / null storeType | **Pass** — 0 / 0 |
| UP-GAP-07 | `dependency-tree?modulePath=user-profile` | **Pass** — 24 nodes |

Full curl suite: [UserProfileService_Pilot_SignOff.md](../../../../Downloads/DesignDocuments/Docs/UserProfileService_Pilot_SignOff.md).

---

## 8. Re-index

```bash
curl -s -X DELETE "$BASE/admin/index/$SVC"
curl -s -X POST "$BASE/admin/index/local" -H 'Content-Type: application/json' \
  -d '{"orgId":"quotient","path":"/path/to/platform-user-profile","serviceId":"'$SVC'"}'
```
