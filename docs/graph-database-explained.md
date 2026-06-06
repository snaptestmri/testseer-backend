# What is a graph database?

> **Status:** Canonical  
> **Last verified:** 2026-06-05

A **graph database** is a database designed to store **connected data** and answer questions that depend on **how things are linked**, not just what each thing is on its own.

Most databases are good at storing records: users, orders, invoices. Graph databases are good at storing **networks**: who calls whom, what depends on what, who knows whom, which devices talk to which servers.

---

## 1. The graph model

A graph has two building blocks:

### Nodes (vertices)

A **node** is an entity — one "thing" in the system.

Examples:

- A microservice (`orders`)
- A Java class (`com.example.OrderController`)
- A person, product, IP address, document

Each node usually has:

- A **unique ID**
- A **label** (type): `Service`, `Class`, `Person`
- **Properties** (key–value attributes): name, version, FQN, etc.

### Edges (relationships)

An **edge** is a **directed or undirected link** between two nodes.

Examples:

- `orders` **CALLS** `inventory`
- `OrderController` **DEPENDS_ON** `OrderService`
- Alice **KNOWS** Bob

Edges also have:

- A **type** (`CALLS`, `DEPENDS_ON`, `FRIENDS_WITH`)
- Optional **properties** (weight, confidence, HTTP method, since-date)

### Property graph (most common type)

The model used by Neo4j, Amazon Neptune (LPG mode), Memgraph, and Apache AGE is a **property graph**:

- Nodes and edges can both carry properties
- Edges are **typed** and usually **directed** (A → B)
- A node can have **multiple labels** (e.g. `Person` and `Employee`)

Example structure:

```
orders service
  ├── OrderController  --DEPENDS_ON-->  OrderService
  └── endpoint: getOrder  --OUTBOUND_TO-->  InventoryService (inventory service)

orders service  --CALLS-->  inventory service
```

That structure **is** the data model. The database's job is to store it and let you walk it efficiently.

---

## 2. What "graph-native" actually means

You can represent a graph in **any** database. TestSeer does exactly that in Postgres:

- `graph_nodes` — one row per node
- `graph_edges` — one row per edge (`from_node`, `to_node`, `edge_type`)

That is **graph data in a relational database**.

A **native graph database** is different in **how it stores and queries** that data:

| Aspect | Relational (Postgres + tables) | Native graph DB (e.g. Neo4j) |
|--------|--------------------------------|------------------------------|
| Primary unit | Row in a table | Node + its relationships |
| Traversal | JOINs or recursive CTEs | Index structures that follow edges directly |
| Query style | SQL | Cypher, Gremlin, openCypher, etc. |
| Mental model | "Join these tables" | "Match this pattern in the graph" |

### Why traversal matters

Suppose you ask: *"If I change `OrderDto`, what is affected upstream?"*

You must walk **backward** along edges: classes that use the type → endpoints that call those classes → services that call those endpoints → …

In SQL (what TestSeer does):

```sql
WITH RECURSIVE affected(node_id) AS (
  SELECT from_node FROM graph_edges WHERE to_node = :id
  UNION
  SELECT e.from_node FROM graph_edges e
  JOIN affected a ON e.to_node = a.node_id
)
SELECT * FROM affected;
```

That works. But each hop is a recursive step over a generic table. As graphs grow and queries get more complex (shortest path, all paths, pattern matching), native graph engines often optimize this path-following much more aggressively.

Graph DBs typically use structures like:

- **Adjacency lists** — each node knows its neighbors
- **Index-free adjacency** (Neo4j term) — follow a pointer to the next node without a global index lookup per hop

So "graph database" is not just "we have nodes and edges tables." It is **storage and query execution built for relationship-heavy workloads**.

---

## 3. Query languages

Relational DBs use **SQL**. Graph DBs use **graph query languages**.

### Cypher (Neo4j, Apache AGE, Memgraph)

Pattern-matching style — you describe the **shape** you want:

```cypher
MATCH (changed:Class {fqn: 'com.example.OrderDto'})<-[:USES_TYPE*1..5]-(consumer)
RETURN consumer
```

Read as: "Find classes that use this type, directly or up to 5 hops back."

### Gremlin (Apache TinkerPop, Neptune)

Imperative / traversal style:

```gremlin
g.V().has('Class', 'fqn', 'com.example.OrderDto')
  .in('USES_TYPE').repeat(in()).times(5)
```

### SQL/PGQ (PostgreSQL 19+, emerging)

Postgres is adding a **standard** way to define property graphs over existing tables and query with `MATCH`-like syntax — a bridge between "graph in Postgres" and "graph queries" without leaving SQL entirely.

Example (conceptual):

```sql
CREATE PROPERTY GRAPH dep_graph
  VERTEX TABLES (packages LABEL pkg PROPERTIES (id, name, version))
  EDGE TABLES (
    depends_on
      SOURCE KEY (package_id) REFERENCES packages(id)
      DESTINATION KEY (dependency_id) REFERENCES packages(id)
      LABEL depends
  );

SELECT * FROM GRAPH_TABLE (dep_graph
  MATCH (a IS pkg WHERE a.name = 'my-app')
        -[IS depends]->(b IS pkg)
  COLUMNS (b.name AS dependency, b.version)
);
```

---

## 4. Graph database vs relational database (deep comparison)

### Relational model

Tables, rows, foreign keys:

```
services(id, name)
classes(id, service_id, fqn)
calls(from_service_id, to_service_id)
```

**Strengths:**

- ACID transactions across many entity types
- Mature tooling, backups, replication
- Great for CRUD, reporting, mixed workloads
- One DB for registry, facts, jobs, **and** graph (TestSeer's choice)

**Weaknesses for pure graph work:**

- Deep traversals can get expensive (many JOINs or deep recursion)
- Schema changes when relationship types multiply
- "Find any path matching this pattern" is awkward in SQL

### Graph model

Everything is nodes + edges from the start.

**Strengths:**

- Natural fit for dependency, social, fraud, recommendation, network topology
- Queries read like the problem ("friends of friends", "impact radius")
- Often faster for **local** traversals (neighborhood, paths)

**Weaknesses:**

- Another system to operate (unless using an extension like Apache AGE)
- Bulk analytics across all columns sometimes better in SQL/warehouse
- Not always ideal as the **only** database for an entire app

### The hybrid reality (where TestSeer sits)

TestSeer is a **hybrid**:

1. **Ingest** Java → extract facts
2. **Project** into `graph_nodes` / `graph_edges`
3. **Query** with recursive SQL in `GraphProjectionService`

So you have a **knowledge graph** (conceptually) on **relational storage** (technically). That is a valid and common pattern until scale or query complexity pushes you toward a dedicated graph engine.

---

## 5. Types of graph databases

### Property graph (LPG)

Nodes + typed directed edges + properties on both.

- Neo4j, Memgraph, Amazon Neptune (Gremlin/Cypher), Apache AGE
- Best fit for: dependency graphs, org charts, fraud rings, IT topology

TestSeer's model (`SERVICE`, `CLASS`, `CALLS`, `DEPENDS_ON`) is a property graph.

### RDF / triple stores

Data as **subject – predicate – object** triples:

```
(OrderService, dependsOn, OrderRepository)
(OrderService, rdf:type, Class)
```

- Apache Jena, Blazegraph, some Neptune modes
- Best fit for: linked data, ontologies, semantic web, strict schemas
- Query language: **SPARQL**

TestSeer does **not** use RDF; it uses a property graph shape in SQL.

---

## 6. Core operations graph DBs optimize

| Operation | Meaning | TestSeer use case |
|-----------|---------|-------------------|
| **Neighborhood** | Direct neighbors of a node | "What does this endpoint call?" |
| **Reachability** | All nodes reachable along edge types | "What services does orders call?" |
| **Reverse reachability** | Walk edges backward | **Impact analysis** — who breaks if X changes? |
| **Shortest path** | Minimum hops between A and B | "Path from changed class to external API" |
| **Pattern match** | Subgraph matching a template | "All endpoints that eventually call inventory" |
| **Centrality** | Important / hub nodes | "Which shared types are riskiest?" |

TestSeer implements reachability, reverse reachability, and neighborhood via Postgres CTEs — the **operations** are graph operations; the **engine** is relational. Eight of nine `GraphProjectionService` queries are exposed via REST; `crossServiceBoundary` is internal only (no `/v1/graph/cross-service` route).

---

## 7. When to use a graph database

**Good fit:**

- Relationships are the main question ("connected to", "depends on", "influences")
- Variable-depth traversals are frequent
- Schema is evolving and relationship-heavy
- You need interactive exploration (Neo4j Browser, AGE Viewer, Linkurious)

**Often not worth it:**

- Graph is small and queries are simple (Postgres CTEs are enough — TestSeer's spike conclusion)
- Graph is **derived** from source of truth elsewhere (Java AST, facts tables)
- Team already standardized on Postgres and wants one operational surface
- You mostly need tabular reports, not path exploration

TestSeer's PRD/architecture explicitly chose **Postgres over Neo4j** for production after benchmarking — graph DBs were evaluated, not ignored.

---

## 8. How this maps to TestSeer specifically

End-to-end in the TestSeer backend:

```
Java source files
    → JavaParser + FactExtractor
    → Postgres: symbol_facts, outbound_call_facts
    → GraphFactProjector
    → Postgres: graph_nodes, graph_edges
    → GraphProjectionService (recursive SQL)
    → ImpactAnalysisService
```

**Node types:** `SERVICE`, `CLASS`, `ENDPOINT`, `SHARED_TYPE`

**Edge types:** `CALLS`, `DEPENDS_ON`, `OUTBOUND_TO`, `USES_TYPE`

**Node ID scheme** (from `GraphNodeIds`):

| Type | ID format |
|------|-----------|
| Service | `{serviceId}` |
| Class | `{serviceId}::class::{classFqn}` |
| Endpoint | `{serviceId}::endpoint::{classFqn}#{methodName}` |
| Shared type | `{serviceId}::type::{typeFqn}` |

**Postgres schema** (`V6__graph_schema.sql`):

```sql
CREATE TABLE graph_nodes (
    id          VARCHAR(255) PRIMARY KEY,
    org_id      VARCHAR(100) NOT NULL,
    repo        VARCHAR(255) NOT NULL,
    service     VARCHAR(255) NOT NULL,
    module_type VARCHAR(50)  NOT NULL DEFAULT 'service',
    node_type   VARCHAR(50)  NOT NULL,
    symbol_fqn  VARCHAR(500)
);

CREATE TABLE graph_edges (
    id              BIGSERIAL    PRIMARY KEY,
    from_node       VARCHAR(255) NOT NULL REFERENCES graph_nodes(id),
    to_node         VARCHAR(255) NOT NULL REFERENCES graph_nodes(id),
    edge_type       VARCHAR(50)  NOT NULL,
    confidence      FLOAT        NOT NULL DEFAULT 1.0,
    evidence_source VARCHAR(50)
);
```

**Graph REST API** (`/v1/graph/*`):

| Endpoint | Purpose |
|----------|---------|
| `GET /v1/graph/reachability` | Forward service/class traversal |
| `GET /v1/graph/impact` | Reverse reachability for a node |
| `GET /v1/graph/neighborhood` | Depth-1 neighbours |
| `GET /v1/graph/shared-type` | Shared library type lookup |
| `GET /v1/graph/type-fanout` | All consumers of a type |

When someone says "the ingested graph," they mean this projected network — **not** necessarily "data living inside Neo4j."

---

## 9. Graph database vs graph visualization

These are easy to conflate:

| Term | What it is |
|------|------------|
| **Graph database** | Storage + query engine for connected data |
| **Graph projection** | Building node/edge tables from parsed code (TestSeer ingestion) |
| **Graph visualization** | Drawing the network (Gephi, Cytoscape, AGE Viewer) — **separate** from storage |

You can have graph data in Postgres and still need a **viz tool** to see it. TestSeer has graph storage and query APIs; a dedicated visualization UI is not yet implemented.

**Quick visualization options** (external tools, no TestSeer code required):

- **Gephi** — JDBC import from `graph_nodes` / `graph_edges`
- **Cosmograph** — upload CSV exports (non-commercial license)
- **yEd** — import edge lists via Excel

See [`docs/README.md`](README.md) for the full documentation index.

---

## 10. Key files in the TestSeer codebase

| File | Purpose |
|------|---------|
| `testseer-backend/.../graph/GraphFactProjector.java` | Ingestion → graph projection |
| `testseer-backend/.../graph/GraphProjectionService.java` | Recursive CTE traversal queries |
| `testseer-backend/.../graph/GraphNode.java` | Node record |
| `testseer-backend/.../graph/GraphEdge.java` | Edge record |
| `testseer-backend/.../graph/GraphNodeIds.java` | Deterministic node ID scheme |
| `testseer-backend/.../query/GraphQueryController.java` | REST graph API |
| `testseer-backend/.../analysis/ImpactAnalysisService.java` | PR impact using graph traversal |

Phase 0 Postgres vs Neo4j benchmark code was removed after the storage decision (Postgres selected); results are documented in `TestSeer_Central_Backend_PRD.md` Appendix A and `TestSeer_Phase1_SystemDesign.md` §5.1.

---

## 11. Recursive CTEs vs Cypher for impact analysis

TestSeer's impact pipeline asks: *given a changed class or endpoint, who upstream could break?* That is **reverse reachability** — walk edges backward from the changed node to find consumers.

Both Postgres recursive CTEs and Cypher express the same graph operation. The difference is **syntax**, **what you get back**, and **where complexity starts to hurt**.

### The same query, two languages

**Postgres (production)** — from `GraphProjectionService.reverseReachability`:

```sql
WITH RECURSIVE affected(node_id) AS (
    -- Base: direct callers / dependents
    SELECT from_node FROM graph_edges
    WHERE to_node = :id
      AND edge_type IN ('CALLS', 'DEPENDS_ON', 'USES_TYPE')
    UNION
    -- Recursive: walk one more hop backward
    SELECT e.from_node FROM graph_edges e
    JOIN affected a ON e.to_node = a.node_id
    WHERE e.edge_type IN ('CALLS', 'DEPENDS_ON', 'USES_TYPE')
)
SELECT n.id FROM graph_nodes n
JOIN affected a ON n.id = a.node_id;
```

**Equivalent Cypher** (Neo4j, Apache AGE, or Memgraph):

```cypher
MATCH (start {id: $nodeId})<-[:CALLS|DEPENDS_ON|USES_TYPE*]-(consumer)
RETURN DISTINCT consumer.id;
```

Read the Cypher as: "Starting at the changed node, follow any combination of these edge types backward, any number of hops, and return every node you reach."

For TestSeer's current impact use case, the two queries are **semantically equivalent**.

### Side-by-side comparison

| Dimension | Recursive CTE (TestSeer today) | Cypher |
|-----------|-------------------------------|--------|
| **Readability** | Imperative: base case + UNION + JOIN | Declarative: pattern `(a)<-[:REL*]-(b)` matches the mental model |
| **Edge-type filter** | `edge_type IN (...)` in both base and recursive arms | `[:CALLS\|DEPENDS_ON\|USES_TYPE*]` in one place |
| **Hop limit** | Must add `WHERE depth < N` and track depth in the CTE | Native: `*1..5` for bounded traversal |
| **Return value** | Node IDs only (then join `graph_nodes` if you need properties) | Nodes, paths, or properties in one `RETURN` |
| **Cycle handling** | Postgres deduplicates via set semantics in UNION; no built-in path tracking | `*`` can revisit nodes; use `shortestPath` or `DISTINCT` |
| **Mix with SQL facts** | Natural — same transaction as `symbol_facts`, `outbound_call_facts` | Requires wrapping Cypher in SQL (`cypher()` in AGE) or app-side joins |
| **Phase 0 p95 (40 services)** | ≤ 3 ms | 7–14 ms (Neo4j spike; separate deployment) |

### How impact analysis actually uses the graph

Impact is not a single graph query. `ImpactAnalysisService` runs a **multi-step pipeline**:

1. Load **changed symbols** from `symbol_facts` (commit-scoped, relational).
2. For each changed CLASS/ENDPOINT, resolve a **graph node ID** (`GraphNodeIds.classNode`, `endpointNode`).
3. Call **`reverseReachability(nodeId)`** — the recursive CTE above.
4. **Cross-match outbound calls** — SQL over `outbound_call_facts` when graph edges alone miss cross-service HTTP links.
5. Load **downstream dependencies**, **test class suggestions**, and **missing test classes** — all relational.

```text
symbol_facts (SQL)  ──►  node IDs  ──►  reverseReachability (CTE)
                                              │
outbound_call_facts (SQL) ◄── fallback ────────┘
         │
         ▼
   ImpactReport (changed, consumers, downstream, tests)
```

Cypher excels at step 3 in isolation. Steps 1, 4, and 5 are **relational** and benefit from staying in SQL. That is a major reason TestSeer kept Postgres: impact analysis is a **hybrid** of graph traversal and fact-table joins, not pure graph navigation.

### Query-by-query mapping

| TestSeer operation | CTE approach | Cypher equivalent |
|--------------------|--------------|-----------------|
| Forward service calls | Recursive forward on `CALLS`, filter `node_type = 'SERVICE'` | `MATCH (s:Service {id: $id})-[:CALLS*]->(down:Service) RETURN down` |
| Class dependencies | Recursive forward on `DEPENDS_ON` | `MATCH (c:Class {id: $id})-[:DEPENDS_ON*]->(dep:Class) RETURN dep` |
| **Impact (reverse)** | Recursive backward on 3 edge types | `MATCH (n {id: $id})<-[:CALLS\|DEPENDS_ON\|USES_TYPE*]-(affected) RETURN affected` |
| Cross-service boundary | CTE + JOIN on `graph_nodes.service` to stop at boundary | `MATCH path = (start)-[:OUTBOUND_TO\|CALLS*]->(ext) WHERE ext.service <> start.service RETURN ext` |
| Shared type fan-out | Single-hop JOIN (no recursion) | `MATCH (consumer)-[:USES_TYPE]->(t:SharedType {symbol_fqn: $fqn}) RETURN consumer` |
| Immediate neighborhood | One-hop SELECT (outbound only today) | `MATCH (n {id: $id})-[r:CALLS\|DEPENDS_ON\|OUTBOUND_TO]-(neighbor) RETURN neighbor, type(r)` |

### Where CTEs start to struggle

Recursive CTEs remain the right tool until one or more of these appear:

| Pain signal | Why CTEs get awkward | How Cypher helps |
|-------------|----------------------|------------------|
| **Return the path**, not just endpoints | CTEs track node IDs; reconstructing paths needs extra state columns | `RETURN path` or `nodes(path)` built-in |
| **Variable patterns** | e.g. "endpoint → class → service → external service, but skip SHARED_TYPE unless confidence > 0.8" | Pattern alternation and property filters in one `MATCH` |
| **Shortest / bounded path** | Manual depth counter in CTE; planner may not optimize well | `shortestPath`, `*1..5` |
| **Graph algorithms** | PageRank, betweenness — not in SQL | `gds.*` (Neo4j) or AGE optional algorithms |
| **Portfolio > ~500 services** | CTE planner cost grows; Phase 1 design cites ~500 as ceiling | Native graph storage scales traversal better |
| **P95 traversal > 50 ms** | Revisit trigger in Phase 1 system design | Dedicated graph engine or AGE |

### Where CTEs remain better (for TestSeer today)

- **Single database, single transaction** — graph projection, facts, and impact in one Postgres commit boundary.
- **Team familiarity** — SQL is already used everywhere in the backend; no Cypher specialist required.
- **Proven latency** — Phase 0 spike: Postgres ≤ 3 ms p95 vs Neo4j 7–14 ms at 40 services (different deployment, but directionally favorable).
- **Simple traversals** — reverse reachability with a fixed edge-type set is exactly what recursive CTEs were designed for.
- **Derived graph** — the source of truth is parsed Java + fact tables; the graph is a projection. Relational storage matches the write path.

**Bottom line for impact analysis:** CTEs and Cypher solve the same core question ("who is affected upstream?") with equivalent correctness. TestSeer chose CTEs because impact is mostly **ID-list reachability plus relational enrichment**, not complex path-pattern mining — and Postgres already hosts everything else.

---

## 12. When Apache AGE is worth adding (without leaving Postgres)

[Apache AGE](https://age.apache.org/) is a **PostgreSQL extension** that adds openCypher graph queries on top of the same Postgres instance. It is not a separate database like Neo4j — you keep Cloud SQL / docker-compose Postgres and add the extension.

That matters for TestSeer: AGE is a way to get **Cypher + graph visualization** without migrating off Postgres or running a second operational system.

### Three layers to separate

```text
┌─────────────────────────────────────────────────────────┐
│  Layer 1: Source of truth (keep as-is)                  │
│  symbol_facts, outbound_call_facts, service_registry    │
└──────────────────────────┬──────────────────────────────┘
                           │ GraphFactProjector
┌──────────────────────────▼──────────────────────────────┐
│  Layer 2: Graph storage (choose one or both)            │
│  A) graph_nodes / graph_edges  (relational, today)      │
│  B) AGE graph "testseer"       (Cypher-native, optional)│
└──────────────────────────┬──────────────────────────────┘
                           │ Query
┌──────────────────────────▼──────────────────────────────┐
│  Layer 3: Traversal + viz                               │
│  GraphProjectionService (CTE)  OR  Cypher via AGE       │
│  Gephi / AGE Viewer / custom UI                         │
└─────────────────────────────────────────────────────────┘
```

You do **not** have to replace Layer 2A with 2B. The usual pattern is **dual projection**: `GraphFactProjector` continues writing relational tables (for SQL joins and existing APIs), and optionally mirrors the same nodes/edges into an AGE graph for Cypher queries and AGE Viewer.

### What AGE adds on top of plain Postgres

| Capability | Plain Postgres (today) | With Apache AGE |
|------------|------------------------|-----------------|
| Traversal queries | Recursive CTEs in Java strings | Cypher via `SELECT * FROM cypher('testseer', $$ ... $$) AS (...)` |
| Interactive exploration | External tools (Gephi, SQL export) | [AGE Viewer](https://github.com/apache/age-viewer) — web UI, Cypher console |
| Pattern expressiveness | Grows verbose quickly | `MATCH` patterns, variable-length paths, path returns |
| Operational footprint | Zero change | Install extension; PG 16 supported |
| Join with fact tables | Direct SQL | Wrap Cypher in SQL or join AGE results to relational tables |

### What AGE costs

| Cost | Detail |
|------|--------|
| **Extension ops** | Install AGE on Postgres (supported in PG 16; available via `apache/age` Docker image). Managed Cloud SQL may require allowlisting extensions. |
| **Dual write** | `GraphFactProjector` must also `CREATE`/`MERGE` nodes and edges in AGE, or a batch sync job runs after each index. |
| **Query wrapper** | AGE Cypher runs inside SQL: `SELECT * FROM cypher('graph_name', $$ MATCH ... $$) AS (id agtype);` — AgType deserialization in Java. |
| **Consistency** | Two representations can drift if sync fails; need idempotent projection or transactional dual-write. |
| **Team skill** | Cypher for ad-hoc queries and viz; Java service code can stay on CTEs until you deliberately switch. |
| **Not a perf magic bullet** | AGE still runs on Postgres storage; deep traversals may improve query ergonomics before they improve latency. Revisit when CTE P95 degrades, not preemptively. |

### Integration sketch (no full migration)

After each successful `GraphFactProjector.project()`:

```sql
-- Illustrative: upsert a node into AGE graph "testseer"
SELECT * FROM cypher('testseer', $$
  MERGE (n:Class {id: 'svc-orders::class::com.example.OrderController'})
  SET n.service = 'orders', n.symbol_fqn = 'com.example.OrderController'
$$) AS (n agtype);

-- Illustrative: upsert an edge
SELECT * FROM cypher('testseer', $$
  MATCH (a {id: $from}), (b {id: $to})
  MERGE (a)-[:DEPENDS_ON]->(b)
$$, $from, $to) AS (e agtype);
```

Production code would batch these in the same transaction as relational upserts, or run a post-index sync job keyed by `analysis_runs` completion.

Impact analysis could then optionally use Cypher for step 3 while keeping steps 1, 4, 5 in SQL — or keep CTEs in Java and use AGE only for **engineering exploration** via AGE Viewer.

### Decision matrix: when to add AGE

| Signal | Add AGE? | Rationale |
|--------|----------|-----------|
| Graph fits in Postgres, P95 ≤ 50 ms, team happy with SQL | **No** | Current architecture is sufficient |
| Engineers need **interactive graph exploration** without Gephi/CSV export | **Yes (AGE Viewer)** | Primary win is viz + ad-hoc Cypher, not storage migration |
| Queries need **paths, hop limits, or complex patterns** in product features | **Yes (query layer)** | Cypher reduces Java string SQL complexity |
| Portfolio approaching **200+ services** or CTE P95 **> 50 ms** | **Evaluate AGE first, then Neo4j** | Phase 1 revisit trigger; AGE is lower ops than a new database |
| Managed Postgres **does not allow AGE extension** | **No / defer** | Use external viz (Gephi) or subgraph API + Cytoscape instead |
| Need **graph algorithms** (centrality, community detection) on indexed code | **Yes** | AGE + algorithm libraries; hard in pure CTEs |
| Want **zero dual-write complexity** | **No** | Stay on CTEs; add `/v1/graph/subgraph` API for viz only |

### Recommended phasing for TestSeer

**Phase A — No AGE (now)**  
Keep `graph_nodes` / `graph_edges` + recursive CTEs. Add subgraph export API or Gephi SQL for visualization if needed.

**Phase B — AGE for exploration only**  
Install AGE on dev/staging Postgres. Mirror graph after indexing. Use AGE Viewer for calibration and debugging projection accuracy. Production impact API unchanged.

**Phase C — AGE for selected queries**  
Move `crossServiceBoundary` or path-returning impact queries to Cypher where CTEs become unmaintainable. Keep relational tables for joins with `symbol_facts`.

**Phase D — Revisit dedicated graph DB**  
Only if AGE on Postgres still misses SLOs at 500+ services — then Neo4j/Neptune becomes worth the operational cost (Phase 0 spike already benchmarked this path).

### AGE vs Neo4j vs SQL/PGQ (quick reference)

| Option | Leaves Postgres? | Best for TestSeer when… |
|--------|------------------|-------------------------|
| **Recursive CTEs** | No | Default; proven at current scale |
| **Apache AGE** | No (extension) | Need Cypher + AGE Viewer without second DB |
| **PostgreSQL 19 SQL/PGQ** | No (standard) | Future: standard `MATCH` over `graph_nodes`/`graph_edges` without AGE |
| **Neo4j / Neptune** | Yes (separate store) | Portfolio > 500 services or AGE still too slow |

**Bottom line:** Apache AGE is worth adding when you need **Cypher ergonomics or built-in graph exploration** on the same Postgres you already run — not because recursive CTEs are wrong for impact analysis today. Treat AGE as an **optional mirror + query/viz layer** on top of relational graph tables, not a replacement for the ingestion pipeline or fact store.

---

## 13. Summary

A **graph database** is a system where:

1. **Data** is modeled as **nodes** and **relationships** (edges), not only as flat tables.
2. **Queries** focus on **traversal and patterns** ("what connects to what, how far, in which direction?").
3. **Storage and indexes** are tuned so following links is cheap.

**TestSeer** stores a **dependency / call graph** that behaves like a property graph, but implements it in **Postgres** with normal tables and recursive SQL. That is a deliberate engineering trade-off: one database, good enough traversal for current scale, without running a second graph-specific system.

---

## Related reading

- [TestSeer_Central_Backend_PRD.md](TestSeer_Central_Backend_PRD.md) — Appendix A: Postgres vs Neo4j decision
- [TestSeer_Phase1_SystemDesign.md](TestSeer_Phase1_SystemDesign.md) — §5.1 Postgres CTEs vs Neo4j (benchmarks and revisit triggers)
- [archive/plans/2026-05-21-p5-graph-projection.md](archive/plans/2026-05-21-p5-graph-projection.md) — Graph projection implementation plan (historical)
- [../README.md](../README.md) — Backend architecture and API overview
- [Apache AGE](https://age.apache.org/) / [AGE Viewer](https://github.com/apache/age-viewer) — Postgres graph extension and web UI
