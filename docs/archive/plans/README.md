# Historical implementation plans

> **Status:** Historical  
> **Last verified:** 2026-06-05

These are **agent implementation plans** written during Phase 1 backend and MCP delivery. They are an audit trail, not the source of truth for current behavior.

For how the system works today, use:

- [testseer-backend/README.md](../../../testseer-backend/README.md)
- [testseer-backend/docs/openapi.yaml](../../../testseer-backend/docs/openapi.yaml)
- [TestSeer_Phase1_Architecture.md](../../TestSeer_Phase1_Architecture.md)

## Plans

| Plan | Topic |
|------|--------|
| [2026-05-21-p1-foundation-schema.md](2026-05-21-p1-foundation-schema.md) | Flyway schema, foundation tables |
| [2026-05-21-p2-service-registry.md](2026-05-21-p2-service-registry.md) | Service registry CRUD |
| [2026-05-21-p3-webhook-ingestion.md](2026-05-21-p3-webhook-ingestion.md) | GitHub webhook ingestion |
| [2026-05-21-p4-analysis-workers.md](2026-05-21-p4-analysis-workers.md) | Kafka workers, parsing pipeline |
| [2026-05-21-p5-graph-projection.md](2026-05-21-p5-graph-projection.md) | Graph nodes/edges, CTE queries |
| [2026-05-21-p6-query-api.md](2026-05-21-p6-query-api.md) | Query API, cache, freshness |
| [2026-06-03-p7-outbound-call-facts.md](2026-06-03-p7-outbound-call-facts.md) | Outbound HTTP call facts |
| [2026-06-03-p8-index-trigger.md](2026-06-03-p8-index-trigger.md) | Admin index trigger |
| [2026-06-03-p9-org-discovery.md](2026-06-03-p9-org-discovery.md) | GitHub org discovery |
| [2026-06-03-p10-local-folder-indexing.md](2026-06-03-p10-local-folder-indexing.md) | Local folder indexing |
| [2026-06-05-p11-impact-analysis.md](2026-06-05-p11-impact-analysis.md) | PR impact analysis |
| [2026-06-05-p12-gap-detection.md](2026-06-05-p12-gap-detection.md) | Test gap detection |
| [2026-06-05-p13-parser-semantic-enrichment.md](2026-06-05-p13-parser-semantic-enrichment.md) | Parser semantic enrichment |
| [2026-06-05-p14-mcp-server.md](2026-06-05-p14-mcp-server.md) | MCP server |
| [2026-06-05-p14-pr-comment-bot.md](2026-06-05-p14-pr-comment-bot.md) | PR comment bot (planned) |
| [2026-06-05-p15-intellij-impact-consumer.md](2026-06-05-p15-intellij-impact-consumer.md) | IntelliJ impact consumer (planned) |
| [2026-06-12-p16-rest-api-hardening.md](2026-06-12-p16-rest-api-hardening.md) | REST API conventions, ApiError, OpenAPI sync (**R1–R3 implemented; R4 planned**) |

Many plans include a **Status: Implemented** header at the top; treat the backend README and OpenAPI spec as authoritative when they diverge.

**P16 REST hardening:** design in [TestSeer_REST_API_Design.md](../../TestSeer_REST_API_Design.md); phases R1 (OpenAPI) → R4 (header `X-TestSeer-Api-Version`).

**P12 gap detection:** **Shipped** (BL-020/021) — `GET /v1/gaps` + MCP `testseer_get_gaps`. Historical plan: [2026-06-05-p12-gap-detection.md](2026-06-05-p12-gap-detection.md).
