# TestSeer — Data Object Catalog: Implementation Caveats

> **Status:** Canonical (implementation-backed)  
> **Last verified:** 2026-06-12  
> **Scope:** Phases 1–5 as shipped in `testseer-backend`  
> **Related:** [TestSeer_Data_Object_Catalog_Design.md](TestSeer_Data_Object_Catalog_Design.md) · [features/10-data-object-catalog.md](features/10-data-object-catalog.md)

This document lists **known caveats, false-positive/false-negative risks, and design-vs-code gaps** for the static persistence catalog. It is intended for QA engineers, agents using MCP traces, and maintainers extending extractors.

---

## 1. Executive summary

The data object catalog is **regex- and convention-driven static analysis**, not runtime ORM introspection. It works well for Quotient `platform-data` patterns (`@Entity` + `@Table`, `*Dao.saveToDb`, `JpaRepository` generics) but will miss or mislabel code that deviates from those conventions.

| Risk level | Area | Typical symptom |
|------------|------|-----------------|
| **High** | Handler linker without catalog join | Wrong `physicalName` (snake_case guess), default `MARIADB` |
| **High** | Library not indexed before consumers | Empty catalog joins; stale event-flow reads/writes |
| **Medium** | `domain_fqn` heuristic | Extra path segments (`entities.offer`) or null domain |
| **Medium** | FQN resolution ambiguity | Wrong accessor FQN when simple names collide across libraries |
| **Medium** | DDL validation | False `INFERRED_NOT_IN_DDL` when catalog/keyspace differs from DDL path |
| **Low** | Mongo physical name fallback | Collection name guessed from class name, not `@Document` |
| **Low** | BigQuery mirrors | Async lag and poll timing not modeled at index time |

**Safe use:** Treat catalog output as **test-planning hints** with `confidence` and `evidenceSource`. Confirm critical assertions via rule pack entries, DDL, or live DB poll.

---

## 2. Architectural caveats

### 2.1 Catalog lives in a separate library index

- Entity and accessor facts are written only when `moduleType = library` (e.g. `optimus-platform-framework` / `platform-data`).
- Handler touchpoints are written from **service** indexes and join catalog at query time via `org_id` + FQN.
- **Caveat:** If the library is missing, stale, or indexed after consumers, joins fail silently and fall back to heuristics (see §5).

### 2.2 Library pinning (shipped BL-002 + BL-036)

`catalogLibraries` in `workspace.yml` and org-scoped DB config (`workspace_catalog_library`) drive pinned joins. `CatalogResolverService` and `HandlerAccessLinker` use `WorkspaceCatalogService.pinnedCatalogLibraryIdsForService(orgId, moduleId)`.

**Remaining caveat:** If multiple library registrations exist for the same FQN without pinned config, org-wide fallback still picks latest `indexed_at` — configure pins via workspace API or YAML.

### 2.3 Query-time rule pack overlay

`DataObjectMergeService` applies `quotient-messaging.yml` `dataObjects` at read time. Rule pack is loaded at process start (`MessagingRulePackLoader`).

**Caveats:**

- YAML changes require backend restart.
- No `rulePackHash` in API responses (design ADR-DOC-002 future item).
- Rule wins on conflict; inferred catalog facts are overwritten without persisting the override.

### 2.4 Freshness is per-service, join is cross-service

Event-flow responses can be `CURRENT` for the handler service while the library catalog is `STALE`. Stale-library propagation is design intent; not all query paths surface an explicit warning today.

---

## 3. Phase 1 — Entity catalog (`EntityCatalogExtractor`)

**Implementation:** `EntityCatalogExtractor.java`, `StoreTypeInferencer.java`

### 3.1 Regex-only annotation parsing

Physical names and catalogs are extracted with regex, not JavaParser AST:

```18:23:testseer-backend/src/main/java/io/testseer/backend/ingestion/catalog/EntityCatalogExtractor.java
    private static final Pattern JPA_TABLE =
            Pattern.compile("@Table\\s*\\(\\s*name\\s*=\\s*\"([^\"]+)\"(?:[^)]*catalog\\s*=\\s*\"([^\"]+)\")?");
    private static final Pattern DOCUMENT_COLLECTION =
            Pattern.compile("@Document\\s*\\(\\s*(?:collection\\s*=\\s*)?\"([^\"]+)\"");
    private static final Pattern CASSANDRA_TABLE =
            Pattern.compile("@Table\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"");
```

| Gap | Impact |
|-----|--------|
| Single-quoted annotation values (`name = 'Foo'`) | Missed; falls back to class-name heuristic |
| Multiline / wrapped annotations | First-line match may fail |
| `@Table(schema = "...")` without `catalog` | `catalog_or_keyspace` stays null |
| `@Document` without explicit `collection` | Falls back to `camelToSnake(simpleName)` — may not match runtime collection |
| JPA `@Entity` without `@Table` | Physical name = `simpleName` with `Entity` stripped — may not match DB table |

### 3.2 Store type inference

```11:24:testseer-backend/src/main/java/io/testseer/backend/ingestion/catalog/StoreTypeInferencer.java
    public StoreType inferFromEntity(String classFqn, List<String> annotations, String content) {
        if (annotations.contains("Document")) {
            return StoreType.MONGODB;
        }
        if (annotations.contains("Entity")) {
            StoreType pkg = StoreType.fromPackageHint(classFqn);
            return pkg != StoreType.UNKNOWN ? pkg : StoreType.MARIADB;
        }
        if (content != null && content.contains("@Table") && classFqn != null
                && classFqn.contains(".nosql.")) {
            return StoreType.CASSANDRA;
        }
        ...
    }
```

| Gap | Impact |
|-----|--------|
| `@Entity` in non-RDB package defaults to **MARIADB** | Mislabels hybrid or misplaced entities |
| Cassandra `@Table` outside `.nosql.` package | Skipped (`UNKNOWN` → not emitted) |
| JPA `@Table` on Cassandra class (name collision) | Design disambiguation via Spring Data Cassandra dependency **not implemented** |
| Classes with only package hints, no annotations | Emitted only if package contains `.data.mongo.`, `.nosql.`, `.rdb.`, `.mariadb.` |

### 3.3 `domain_fqn` heuristic

```109:118:testseer-backend/src/main/java/io/testseer/backend/ingestion/catalog/EntityCatalogExtractor.java
    static String inferDomainFqn(String entityFqn) {
        if (entityFqn == null || !entityFqn.endsWith("Entity")) return null;
        ...
        int dataIdx = withoutEntity.indexOf(".data.");
        if (dataIdx < 0) return null;
        ...
        return prefix + ".domain." + domainSuffix;
    }
```

| Gap | Impact |
|-----|--------|
| Requires class name suffix `Entity` | No domain for `PartnerOfferCallRecorder` (no suffix) |
| Requires `.data.` in FQN | Legacy packages without `data` segment → null domain |
| Path segment preservation | Maps `...data.rdb...entities.offer.PartnerOfferCallRecorderEntity` → `...domain.offer.entities.offer.PartnerOfferCallRecorder` (keeps intermediate `entities.offer`; design examples often omit this) |
| Mongo path handling | Strips after last `.entity.` — non-standard mongo layouts may map incorrectly |

**Confidence interaction:** When heuristic `domain_fqn` is present, confidence is capped at **0.70**; when absent, store-level confidence stays **0.95**. A populated domain is therefore *lower* confidence than no domain — by design for Phase 1 heuristics.

### 3.4 Deduplication key

Dedup uses `entityFqn|storeType|physicalName`. Same entity with two physical names (multi-table mapping) produces two rows — correct. Same physical name via different entity classes in error produces duplicate catalog entries.

---

## 4. Phase 1 — Repo generics (`RepoGenericExtractor`)

| Caveat | Detail |
|--------|--------|
| Generic type param must appear literally in `extends` clause | Bounded types, wildcards, or type aliases not parsed |
| Entity link is by **simple name** match to catalog | Colliding `FooEntity` simple names across packages → wrong link |
| Store from repo interface | Falls back to package hint when `extends` clause unrecognized |

---

## 5. Phase 2 — DAO tracing and handler linker

**Implementation:** `DaoMethodExtractor.java`, `HandlerAccessLinker.java`, `CatalogResolverService.java`

### 5.1 DAO method extraction

| Caveat | Detail |
|--------|--------|
| Method signatures via regex | Annotations on methods (`@Modifying`, `@Query`) can break signature match |
| `mapDomainToEntity` parsing | Only explicit body patterns; Lombok-generated or delegated mappers missed |
| Interface-only DAOs without impl in same index | Entity resolution weaker (domain param only) |
| Operation inference from method name | `saveToDb` → WRITE; `find*` → READ; custom names default by prefix list — may misclassify |

### 5.2 Handler access linker

Call detection pattern:

```22:23:testseer-backend/src/main/java/io/testseer/backend/ingestion/catalog/HandlerAccessLinker.java
    private static final Pattern ACCESSOR_CALL =
            Pattern.compile("(\\w+(?:Repo|Dao|Repository|Template))\\.(\\w+)\\s*\\(");
```

| Gap | Impact |
|-----|--------|
| Field name must match `*Repo|*Dao|*Repository|*Template` | `jdbcTemplate`, `entityManager`, raw SQL helpers missed |
| No chained calls | `getDao().save()` not matched |
| Local variables / factory returns | Only field + constructor injection via `FieldTypeIndex` |
| `MongoTemplate` / `JdbcTemplate` | Not in suffix list (Phase 3 partial coverage elsewhere) |

**Enclosing method detection** scans backward for last `public|protected` method — skips private helpers, may attribute nested anonymous/lambda calls to wrong outer method.

### 5.3 Fallback when catalog join fails

```128:141:testseer-backend/src/main/java/io/testseer/backend/ingestion/catalog/HandlerAccessLinker.java
    private static String inferStoreType(String accessorFqn) {
        ...
        return "MARIADB";
    }
    private static String inferTableFromAccessor(String accessorFqn, String methodName) {
        ...
        return camelToSnake(simple);
    }
```

When `accessor_method_facts` / `data_object_facts` lookup misses:

- `store_type` defaults to **MARIADB**
- `physical_name` becomes snake_case of accessor simple name (e.g. `partner_offer_call_recorder` not `PartnerOfferCallRecorder`)
- Confidence **0.80** with evidence `HANDLER_LINKER` only

This reproduces pre–Phase 2 behavior and is the **primary false-positive source** for event-flow DB steps.

### 5.4 Correlation keys

`correlationKeys()` uses method-name substring heuristics and defaults to `["offerId"]` — not derived from entity schema or rule pack unless merge runs at query time.

---

## 6. Phase 3 — Multi-store and mirrors

**Implementation:** `MirrorStoreExtractor`, `CassandraQueryExtractor`, `MongoAccessExtractor`, `BigQueryDirectExtractor`

| Caveat | Detail |
|--------|--------|
| `@LogForBigQuerySync` | Mirror is **secondary**; async timing not modeled |
| Cassandra `@Query` string parsing | Only quoted table names in known patterns; dynamic CQL missed |
| BigQuery direct writes | Physical name from string literals / config keys — lower confidence |
| Mongo via handler | Service-index extractors may run without library entity link |
| `secondary_stores` JSON | Attached when entity FQN resolves; orphan BQ rows if primary store link fails |

---

## 7. Phase 4 — Import-aware FQN resolution

**Implementation:** `ImportIndex.java`, `TypeFqnResolver.java`, `FieldTypeIndex.java`

### 7.1 Import index limitations

```8:9:testseer-backend/src/main/java/io/testseer/backend/ingestion/catalog/ImportIndex.java
/** Phase 2: simple import-line index for FQN resolution (Phase 4 replaces with full PSI). */
```

| Gap | Impact |
|-----|--------|
| Star imports ignored | Types only in `import com.foo.*` resolve via same-package fallback (0.50 confidence) |
| Static imports | Parsed but not used for type resolution |
| Same simple name in two imports | Last import wins — silent wrong FQN |
| No inner / nested class imports | Resolution fails |

### 7.2 Tiered resolution

| Tier | Source | Confidence | Caveat |
|------|--------|------------|--------|
| 1 | Explicit import | 0.95 | Requires import line present |
| 2 | Same-service `symbol_facts` | 0.85 | `LIKE '%.Name'` — ambiguous |
| 3 | Org library catalog simple name | 0.80 | First match across libraries |
| 4 | Same-package fallback | 0.50 | **Wrong FQN** for injected library DAOs without import |

**Not implemented:** `LibraryClasspathBuilder`, JavaParser `CombinedTypeSolver`, Maven dependency classpath (design Phase 4b).

### 7.3 Field type index

Field/constructor detection uses regex on source text:

- Misses `@Autowired` on separate line from field in some formatting styles
- Does not resolve types from setter `@Autowired` methods
- Generic field types strip generics via simple regex — complex bounds may truncate incorrectly

---

## 8. Phase 5 — DDL validation, merge, gaps

**Implementation:** `SchemaDdlExtractor`, `DataObjectValidationService`, `DataObjectMergeService`, `DataObjectGapService`

### 8.1 DDL extractor

| Caveat | Detail |
|--------|--------|
| Minimal `CREATE TABLE` regex | Views, alters, partitioned tables, quoted mixed-case names may be skipped or misparsed |
| MariaDB `catalog_or_keyspace` | From regex group or path heuristic — may not match JPA `@Table(catalog=...)` |
| Cassandra keyspace | From `USE` statement or path — mismatch with runtime keyspace |
| Mongo / BigQuery DDL | **Not in scope** — no schema_object_facts for those stores |

### 8.2 Validation join

`DataObjectValidationService` matches on `(store_type, physical_name)` with optional catalog — gaps SQL in `DataObjectGapService` **does not compare `catalog_or_keyspace`**, so tables with same name in different schemas can produce false `INFERRED_NOT_IN_DDL` or missed matches.

### 8.3 Gaps API (`GET /v1/gaps/data-objects`)

**Implemented gap codes:**

- `INFERRED_NOT_IN_DDL`
- `DDL_UNREFERENCED`

**Design gap codes not implemented:**

| Code | Meaning |
|------|---------|
| `HANDLER_WITHOUT_CATALOG` | Touchpoint with no library accessor/entity |
| `LIBRARY_NOT_INDEXED` | Org missing platform-data index |
| `RULE_OVERRIDE_APPLIED` | Informational rule pack overlay |

No bundle-scoped filtering (`bundle=quotient-full`) — org-wide scan only.

### 8.4 Rule pack merge

Merge precedence at query time: rule pack → catalog columns on view → legacy fields. Rule matching is by normalized `physicalName` / `entityFqn` keys — aliases and typos miss.

---

## 9. Cross-cutting persistence patterns (unsupported or partial)

| Pattern | Status | Mitigation |
|---------|--------|------------|
| Raw SQL / `JdbcTemplate` / `@Query` native SQL | Not extracted | Rule pack `dataObjects` + manual poll hints |
| Dynamic table/collection names | Not extracted | Low confidence; rule pack |
| `@Embedded` / `@ElementCollection` | Not modeled | — |
| Multi-tenant schema routing | Not modeled | — |
| Stored procedures | Not modeled | — |
| Reactive drivers (R2DBC) | Not modeled | — |
| Cross-database joins in one handler | Single touchpoint per call | — |
| Test / H2 profile entities | Indexed same as prod entities | Filter by env not supported |

---

## 10. Operational caveats

| Requirement | Why it matters |
|-------------|----------------|
| Index `optimus-platform-framework` (`platform-data`) **before** consumer services | Handler linker needs populated `accessor_method_facts` |
| Use `bundles.quotient-full.indexOrder` in `workspace.yml` as catalog-first guidance | Library first is documented; reversing order yields stale joins until re-index |
| Re-index library after `platform-data` entity/DAO changes | Consumers do not re-emit catalog facts |
| Register `riq-platform-db` for Phase 5 DDL validation | Without it, all MariaDB/Cassandra catalog rows trend `INFERRED_NOT_IN_DDL` |
| Curate `quotient-messaging.yml` `dataObjects` for E2E flows | Only guaranteed poll hints and name overrides for curated objects |

---

## 11. Testing blind spots

Unit tests use **minimal Java snippets**, not full `platform-data` files:

- Golden tests cover happy paths (`PartnerOfferCallRecorderEntity`, `SegmentOfferEntity`).
- Integration tests use fixture dirs; not continuous sync with live Quotient repos.
- Regression does not exhaustively scan all entities in `optimus-platform-framework`.

**281/281** backend tests green does **not** imply complete catalog coverage of production entity count.

---

## 12. Design document drift

| Item | Design doc | Shipped code |
|------|------------|--------------|
| Header status | "Design (not implemented)" | Phases 1–5 implemented — update design header when editing |
| `catalogLibraries` config | Specified | **Done** — YAML + org-scoped DB API (V13, `/v1/workspace/*`) |
| `LibraryClasspathBuilder` | Optional Phase 4b | **Done** (BL-002) |
| Org-scoped config API | Not in original design | **Done** — [16-workspace-catalog-config.md](features/16-workspace-catalog-config.md) |
| Package layout `query.catalog` | Separate package | Classes under `io.testseer.backend.query` |
| Gap codes | Six codes | Four codes (`LIBRARY_NOT_INDEXED`, `LIBRARY_STALE`, `HANDLER_WITHOUT_CATALOG`, plus DDL gaps) |
| Feature README index | "planned" on catalog API | Shipped — see `10-data-object-catalog.md` status |

---

## 13. Mitigation playbook (for QA and agents)

1. **Check freshness** — `GET /v1/status/{libraryServiceId}` before trusting event-flow DB steps.
2. **Prefer high-confidence rows** — `confidence >= 0.93` and `evidenceSource` containing `CATALOG` or `DAO_IMPL`.
3. **Use rule pack** — extend `dataObjects` for any object used in E2E trace steps.
4. **Validate DDL** — index `riq-platform-db`; treat `INFERRED_NOT_IN_DDL` as review queue, not blocker.
5. **Call gaps API** — `GET /v1/gaps/data-objects?orgId=quotient` for catalog/DDL drift.
6. **Do not trust defaults** — `store_type=MARIADB` + snake_case `physical_name` without `entity_fqn` is fallback heuristic.

---

## 14. Related documents

- [TestSeer_Multi_Module_Catalog_Requirements.md](TestSeer_Multi_Module_Catalog_Requirements.md) — config, symbol classpath, multi-module registry (P1–P4 shipped)
- [features/16-workspace-catalog-config.md](features/16-workspace-catalog-config.md) — org-scoped workspace catalog REST API
- [TestSeer_Data_Object_Catalog_Design.md](TestSeer_Data_Object_Catalog_Design.md) — target architecture
- [features/10-data-object-catalog.md](features/10-data-object-catalog.md) — requirements and acceptance
- [features/07-option-c-messaging-flow.md](features/07-option-c-messaging-flow.md) — event-flow consumer of enriched `data_access_facts`
- [REQUIREMENTS.md](../../docs/REQUIREMENTS.md) — WRK-18 through WRK-24
