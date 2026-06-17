# TestSeer backend documentation

> **Platform status:** [CURRENT_STATUS.md](../../docs/CURRENT_STATUS.md)  
> **Runbook:** [../README.md](../README.md)  
> **API contract:** [openapi.yaml](openapi.yaml) (regenerate via `scripts/export-openapi.sh`)

## Canonical architecture

| Document | Purpose |
|----------|---------|
| [TestSeer_Phase1_Architecture.md](TestSeer_Phase1_Architecture.md) | System architecture, components, ADRs, API summary |
| [features/README.md](features/README.md) | **End-to-end feature design docs** (index + 29 features) |
| [TestSeer_Phase1_SystemDesign.md](TestSeer_Phase1_SystemDesign.md) | Requirements, trade-offs, scaling triggers |
| [TestSeer_Phase1_User_Stories.md](TestSeer_Phase1_User_Stories.md) | Phase 1 user stories |
| [graph-database-explained.md](graph-database-explained.md) | Graph model, Postgres CTEs vs Cypher, Apache AGE |
| [Option_C_Messaging_Flow.md](Option_C_Messaging_Flow.md) | Option C runbook, curl examples, bulk index |
| [API_REFERENCE.md](API_REFERENCE.md) | **Endpoint catalog** — status codes, errors, freshness, curl examples |
| [../../docs/STORAGE_AND_API_MAP.md](../../docs/STORAGE_AND_API_MAP.md) | **Table → API map** — Postgres/graph storage, response shapes, TE-GAP-02 docs vs reality |
| [TestSeer_TE_GAP_02_Reachability_Hydration_Design.md](TestSeer_TE_GAP_02_Reachability_Hydration_Design.md) | **TE-GAP-02** — reachability subgraph hydration (KFK-04), tests, pilot curls |
| [TestSeer_BL058_Maven_Dependency_Tree_Design.md](TestSeer_BL058_Maven_Dependency_Tree_Design.md) | **BL-058** — Maven dependency tree + artifact versioning (MVN-01–18) |
| [TestSeer_AC_MVN_4_Internal_Artifact_Link_Design.md](TestSeer_AC_MVN_4_Internal_Artifact_Link_Design.md) | **AC-MVN-4** — internal GAV `linkedServiceId`, backfill admin, `OWNED_BY` edges |
| [TestSeer_REST_API_Design.md](TestSeer_REST_API_Design.md) | REST conventions, `ApiError`, freshness HTTP, OpenAPI governance (**P16 R1–R3 shipped**) |

## Feature design docs (E2E)

| # | Feature | Doc |
|---|---------|-----|
| 1 | Service Registry | [features/01-service-registry.md](features/01-service-registry.md) |
| 2 | Ingestion Pipeline | [features/02-ingestion-pipeline.md](features/02-ingestion-pipeline.md) |
| 3 | Fact Query API | [features/03-fact-query-api.md](features/03-fact-query-api.md) |
| 4 | Graph Projection | [features/04-graph-projection.md](features/04-graph-projection.md) |
| 5 | Impact Analysis | [features/05-impact-analysis.md](features/05-impact-analysis.md) |
| 6 | Admin Indexing & Clear | [features/06-admin-indexing.md](features/06-admin-indexing.md) |
| 7 | Option C Messaging Flow | [features/07-option-c-messaging-flow.md](features/07-option-c-messaging-flow.md) |
| 8 | MCP Agent Integration | [features/08-mcp-agent-integration.md](features/08-mcp-agent-integration.md) |
| 9 | Service Description (LLM) | [features/09-service-description.md](features/09-service-description.md) |
| 10 | Data Object Catalog | [features/10-data-object-catalog.md](features/10-data-object-catalog.md) |
| 11 | Entry Triggers | [features/11-entry-triggers.md](features/11-entry-triggers.md) |
| 12 | Data Consistency Hints | [features/12-data-consistency-hints.md](features/12-data-consistency-hints.md) |
| 13 | IDE Cache Push | [features/13-ide-cache-push-notification.md](features/13-ide-cache-push-notification.md) |
| 14 | Airflow Entry Triggers | [features/14-airflow-entry-triggers.md](features/14-airflow-entry-triggers.md) |
| 15 | Live Flow Gates | [features/15-live-flow-gates.md](features/15-live-flow-gates.md) |
| 16 | Workspace Catalog Config | [features/16-workspace-catalog-config.md](features/16-workspace-catalog-config.md) |
| 19 | Live GCP Pub/Sub Verify | [features/19-live-pubsub-verify.md](features/19-live-pubsub-verify.md) |
| 29 | Maven dependency tree | [features/29-maven-dependency-tree.md](features/29-maven-dependency-tree.md) |

## Historical

| Document | Purpose |
|----------|---------|
| [TestSeer_Central_Backend_PRD.md](TestSeer_Central_Backend_PRD.md) | Original PRD; Appendix A — Postgres vs Neo4j decision |
| [archive/plans/](archive/plans/README.md) | P1–P16 implementation plans |

Platform-wide docs (v0.1 engine, schema contracts, doc index): [../../docs/README.md](../../docs/README.md)
