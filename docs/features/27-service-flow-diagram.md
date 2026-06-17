# Feature: Service Flow Diagram Composer (BL-054)

> **Status:** Ready (spec)  
> **Design:** [TestSeer_BL054_Service_Flow_Diagram_Design.md](../TestSeer_BL054_Service_Flow_Diagram_Design.md)  
> **Req IDs:** SFD-01–SFD-20 · GRP-12–GRP-20  
> **Pilot:** `transaction-eval-suite` / manual `TransactionEvalConsumer_ServiceGraph_Manual.md` §6

## Problem

TestSeer indexes the same consumer classes as manual service graphs and exposes routing, gates, and graph edges via separate APIs. Agents comparing manual vs TestSeer call `reachability` with default `type=service` and report **0 nodes** — while `neighborhood` and `routing` are populated. There is no single endpoint that produces a **manual §6-equivalent** flow diagram (Mermaid or structured JSON).

## Goals

- `GET /v1/graph/flow-diagram` composes ingress + code path + processor routing + exits
- Optional `format=mermaid` for docs and agents
- Domain actor **roles** on external suite classes (XN001–XN007 pattern)
- `facts/class` **annotations** on nodes
- Explicit `gaps[]` when messaging egress or reverse impact is missing

## Non-goals

- Runtime branch proof (`TransactionSource` value at execution time)
- Replacing manual behavioral narrative (ack/retry, when X1 fires)

## REST

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/v1/graph/flow-diagram` | Composed service flow — see design doc for params |

**MCP (planned):** `testseer_get_service_flow_diagram`

## Key classes (planned)

| Class | Role |
|-------|------|
| `ServiceFlowDiagramComposer` | Main orchestrator |
| `FlowDiagramAnchorResolver` | `triggerId` / `handlerFqn` / `symbolFqn` → start nodes |
| `FlowDiagramGraphExpander` | BFS over graph_edges + routing + messaging |
| `DomainActorRoleEnricher` | `quotient-domain-actors.yml` |
| `FlowDiagramMermaidRenderer` | Mermaid output |

## Dependencies

- **BL-053** (shipped) — `INVOKES`, `ROUTES_TO`, `/graph/routing`
- **BL-050** (partial) — Kafka publish hops in diagram exits
- **BL-051** (shipped) — HTTP Pub/Sub notification exit
- **BL-052** (shipped) — gate facts for labels

## Acceptance (pilot)

See design doc AC-F1–F9. Primary curl:

```bash
curl -s "http://localhost:8080/v1/graph/flow-diagram?orgId=quotient&serviceId=bd0d2428-0810-4f79-9928-a8366cd74dc1\
&anchor=triggerId:kafka:quot.sales.transaction.pipeline.events:com.quotient.platform.transaction.eval.consumer.transactionevalconsumer\
&packagePrefix=com.quotient.platform.transaction.eval&format=mermaid"
```

## Related

- [04-graph-projection.md](04-graph-projection.md)
- [20-trg-13-reverse-impact.md](20-trg-13-reverse-impact.md) — SFD-18
