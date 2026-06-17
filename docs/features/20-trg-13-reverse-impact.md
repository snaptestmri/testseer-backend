# TRG-13 — Reverse impact: changed handler → inbound triggers

> **Req:** [TRG-13](../../../docs/REQUIREMENTS.md) · **Related:** [TRG-12](21-trg-12-entry-flow-chain.md) · [TRG-14](18-trg-14-first-hop-trigger-enrichment.md) · **Design:** [SOLUTIONS_DESIGN §13](../../../docs/SOLUTIONS_DESIGN.md)

## Problem

`GET /v1/graph/entry-flow` walks **forward** from a trigger. PR and test-planning workflows need the **reverse**: given a changed handler class (or `Class#method`), which inbound entry triggers fan in via `TRIGGERED_BY`?

## Solution

Extend `EntryFlowService` with org-scoped reverse lookup on `entry_trigger_facts.linked_handler_fqn`, exposed as:

```
GET /v1/graph/entry-flow/impact?orgId=&handlerFqn=&serviceId=&env=
```

MCP: optional `handlerFqn` + `orgId` on existing `testseer_get_entry_triggers` routes to the impact endpoint.

## Design decisions

| # | Decision | Choice |
|---|----------|--------|
| 1 | Service class | **Extend** `EntryFlowService` (no separate reverse service) |
| 2 | MCP surface | **Extend** `testseer_get_entry_triggers` (not a 17th tool) |
| 3 | Simple-name fallback | **Org-wide** when exact/`#method` tier finds zero rows (`linked_handler_fqn LIKE '%.SimpleName'`) |
| 4 | `serviceId` on impact | **Optional** narrow filter — see below |

### Decision 4 — optional vs required `serviceId`

| Mode | Params | Behavior |
|------|--------|----------|
| **Optional (shipped)** | `orgId` + `handlerFqn` | Search all registered services in the org. Agent can pass a handler FQN from a PR diff without resolving UUIDs. Returns `serviceId` / `repo` on each hit for follow-up calls. |
| **Required (not shipped)** | `serviceId` + `handlerFqn` | Only searches one service. Faster and avoids org-wide simple-name collisions, but breaks when the agent does not know which service registered the handler, and hides duplicate class names across repos until each service is queried manually. |

When the caller **does** know the service (e.g. from `testseer_detect_service`), pass `serviceId` to narrow exact and fallback tiers.

## Handler matching

Input `handlerFqn` is parsed once:

| Input | Class | Method |
|-------|-------|--------|
| `com.foo.Bar` | `com.foo.Bar` | — |
| `com.foo.Bar#onMessage` | `com.foo.Bar` | `onMessage` |

| Tier | Predicate | `matchKind` |
|------|-----------|-------------|
| 1 | `linked_handler_fqn = :class` [+ `linked_method = :method`] | `EXACT` or `METHOD` |
| 2 | (only if tier 1 empty) `linked_handler_fqn LIKE '%.' \|\| :simpleName` | `SIMPLE_NAME` |

Tier 2 runs **org-wide** within `orgId` (optionally scoped by `serviceId`). Document collision risk: two services with the same simple class name both match.

## API response

`ResponseEnvelope<EntryTriggerImpactReport>`:

```json
{
  "orgId": "quotient",
  "handlerFqn": "com.example.RiqOfferEventConsumer",
  "handlerMethod": null,
  "envLane": "pdn",
  "serviceId": null,
  "triggers": [{
    "serviceId": "…",
    "repo": "riq-partner-adapter-suite",
    "serviceName": "riq-partner-adapter-suite",
    "matchKind": "EXACT",
    "trigger": { "triggerKind": "PUBSUB_SUBSCRIBE", "…": "…" }
  }]
}
```

## Freshness

| Query | Rule |
|-------|------|
| With `serviceId` | `FreshnessResolver.resolve(serviceId)` |
| Org-only | `NOT_INDEXED` if org has zero `entry_trigger_facts`; else `CURRENT` (empty list = handler has no triggers, not missing index) |

## Code map

| File | Role |
|------|------|
| `EntryFlowService.impactByHandler` | JDBC reverse lookup + tier-2 fallback |
| `EntryFlowService.triggersForHandler` | Service-scoped exact match (TRG-14 internal) |
| `EntryTriggerQueryController` | `GET /v1/graph/entry-flow/impact` |
| `testseer-mcp/.../entry-triggers.ts` | Route `handlerFqn` → impact API |
| `EventFlowFirstHopEnricher` | Unchanged — uses service-scoped exact lookup |

## Tests

- `EntryFlowServiceHandlerParsingTest` — FQN parse
- `MessagingFlowIntegrationTest` — exact + simple-name fallback
- `EntryTriggerQueryControllerTest` — impact endpoint
- `tools.test.mjs` — MCP routing for `handlerFqn`

## Non-goals

- Graph reverse walk on `TRIGGERED_BY` edges (SQL v1)
- Graph reverse walk on `TRIGGERED_BY` edges beyond SQL v1 (forward chain: [TRG-12](21-trg-12-entry-flow-chain.md))
- Runtime trigger delivery proof (TRG-NG01)
