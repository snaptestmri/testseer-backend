# TestSeer Backend

Central indexing and analysis platform for [TestSeer](https://github.com/snaptestmri/testseer-mcp). Parses Java services, builds a cross-service knowledge graph, and answers the question: **given a PR that changed X, what should the developer test?**

Companion project: [testseer-mcp](https://github.com/snaptestmri/testseer-mcp) — exposes this API as Cursor MCP tools.

## What it does

1. **Ingest** — GitHub webhooks (PR/push) or admin triggers fetch and parse Java source with JavaParser
2. **Index** — Extract symbol facts, outbound HTTP calls, peripherals, and graph edges into Postgres + MongoDB
3. **Query** — REST API for facts, graph traversal, indexing status, and PR impact analysis
4. **Analyze** — `GET /v1/impact/pr` returns changed symbols, upstream callers, downstream deps, and suggested test scope

## Architecture

```
GitHub webhook / admin trigger
        │
        ▼
   Kafka workers ──► JavaParser ──► FactExtractor
        │                    │
        ▼                    ▼
  Postgres facts      GraphFactProjector ──► graph_nodes / graph_edges
  Mongo parsed_models
        │
        ▼
   REST API (/v1/*) ◄── Redis cache
```

**Stack:** Java 21, Spring Boot 3.3, Postgres 16, MongoDB 7, Redis 7, Kafka, Flyway, JavaParser 3.25

## Quick start

### Prerequisites

- Java 21+
- Maven 3.9+
- Docker (for local infrastructure)

### 1. Start infrastructure

```bash
docker compose up -d
```

Starts Postgres (`5432`), MongoDB (`27017`), Redis (`6379`), and Kafka (`9092`).

### 2. Run the server

```bash
mvn spring-boot:run
```

Server starts at **http://localhost:8080**.

### 3. Register and index a service

```bash
# Register
curl -X POST http://localhost:8080/registry/services \
  -H "Content-Type: application/json" \
  -d '{
    "orgId": "acme",
    "repo": "orders",
    "serviceName": "orders",
    "buildTool": "MAVEN",
    "moduleType": "service"
  }'

# Trigger index (replace SERVICE_ID)
curl -X POST http://localhost:8080/admin/index/SERVICE_ID

# Check status
curl http://localhost:8080/v1/status/SERVICE_ID
```

### 4. Get impact analysis

```bash
curl "http://localhost:8080/v1/impact/pr?serviceId=SERVICE_ID&commitSha=COMMIT_SHA"
```

## Swagger / OpenAPI

All REST controllers are annotated with OpenAPI 3 metadata (springdoc). With the server running:

| Resource | URL |
|----------|-----|
| **Swagger UI** | http://localhost:8080/swagger-ui/index.html |
| **OpenAPI JSON** | http://localhost:8080/v3/api-docs |
| **OpenAPI YAML** | http://localhost:8080/v3/api-docs.yaml |

A static spec is checked in at [`docs/openapi.yaml`](docs/openapi.yaml) for clients that do not run the server (e.g. code generators, the MCP server).

Regenerate after API changes:

```bash
mvn test -Dtest=OpenApiExportTest
```

Download from a running server:

```bash
./scripts/export-openapi.sh
# or
curl -s http://localhost:8080/v3/api-docs.yaml -o docs/openapi.yaml
```

API tags in Swagger UI: **Service Registry**, **Webhook**, **Admin — Indexing**, **Admin — Discovery**, **Query — Facts**, **Query — Graph**, **Query — Status**, **Analysis**.

## API overview

| Area | Endpoint | Description |
|------|----------|-------------|
| **Registry** | `POST /registry/services` | Register a service |
| | `GET /registry/services` | List all services |
| **Ingestion** | `POST /webhook/github` | GitHub PR/push webhook |
| | `POST /admin/index/{serviceId}` | On-demand GitHub re-index |
| | `POST /admin/index/local` | Index from local filesystem |
| | `POST /admin/discover` | Scan GitHub org for services |
| **Status** | `GET /v1/status/{serviceId}` | Indexing freshness |
| **Facts** | `GET /v1/facts/class` | Symbol facts for a class FQN |
| | `GET /v1/facts/outbound` | Outbound HTTP call facts |
| | `GET /v1/facts/by-file` | Symbols for file paths (MCP PR analysis) |
| **Graph** | `GET /v1/graph/reachability` | Forward service/class traversal |
| | `GET /v1/graph/impact` | Reverse reachability for a node |
| | `GET /v1/graph/neighborhood` | Depth-1 neighbours |
| | `GET /v1/graph/shared-type` | Shared library type lookup |
| | `GET /v1/graph/type-fanout` | All consumers of a type |
| **Analysis** | `GET /v1/impact/pr` | **PR impact analysis** — changed symbols, callers, test suggestions |
| | `GET /v1/services/{id}/description` | LLM-generated service description |
| | `POST /v1/services/{id}/description` | Regenerate description |

All query endpoints return a `ResponseEnvelope` with `freshnessStatus`: `CURRENT`, `STALE`, `INDEXING`, or `NOT_INDEXED`.

## Impact analysis response

`GET /v1/impact/pr?serviceId=&commitSha=` returns:

- **changedSymbols** — classes and endpoints changed at this commit
- **affectedConsumers** — upstream services/classes via graph reachability + outbound call matching
- **downstreamDependencies** — HTTP calls made by changed classes
- **suggestedTestScope** — unit and integration tests to run, with `exists: true/false`
- **missingTestClasses** — production classes with no matching test class

## Configuration

Environment variables (defaults in parentheses):

| Variable | Default | Purpose |
|----------|---------|---------|
| `POSTGRES_URL` | `jdbc:postgresql://localhost:5432/testseer` | Postgres connection |
| `POSTGRES_USER` / `POSTGRES_PASS` | `testseer` | Postgres credentials |
| `MONGODB_URI` | `mongodb://localhost:27017/testseer` | MongoDB connection |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis cache |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | Kafka brokers |
| `PUBSUB_ENABLED` | `false` | Enable GCP Pub/Sub cache invalidation |
| `ANTHROPIC_ENABLED` | `false` | Enable LLM service descriptions |
| `ANTHROPIC_API_KEY` | — | Anthropic API key |

## Development

```bash
# Run tests (requires Docker for Testcontainers)
mvn test

# Run a single test class
mvn test -Dtest=ImpactAnalysisControllerTest
```

## Project structure

```
src/main/java/io/testseer/backend/
├── registry/          Service registration
├── webhook/           GitHub webhook + Kafka job publishing
├── ingestion/         Workers, JavaParser, fact extraction, dual-write
├── graph/             Graph projection (nodes, edges, CTE queries)
├── query/             Facts, graph, status REST controllers
├── analysis/          Impact analysis + service description
└── admin/             Index triggers, org discovery

src/main/resources/db/migration/   Flyway migrations (V1–V7)
```

## Related projects

- [testseer-mcp](https://github.com/snaptestmri/testseer-mcp) — MCP server for Cursor agent integration
