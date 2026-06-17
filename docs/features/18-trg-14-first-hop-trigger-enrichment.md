# TRG-14 — Event-flow first-hop trigger enrichment

> **Req:** [TRG-14](../../../docs/REQUIREMENTS.md) · **Related:** [TRG-12](21-trg-12-entry-flow-chain.md) · **Design:** [SOLUTIONS_DESIGN §14](../../../docs/SOLUTIONS_DESIGN.md)

## Problem

`GET /v1/graph/event-flow` and `GET /v1/graph/entry-flow` were separate surfaces. The first step of an event-flow trace showed the Pub/Sub subscriber handler but not the indexed **entry trigger** that fans in to that handler (e.g. `PUBSUB_SUBSCRIBE` on `PDN_S.RIQ_OFFER_EVENT`).

## Solution

After building event-flow steps, `EventFlowFirstHopEnricher` looks up `entry_trigger_facts` by `service_id + linked_handler_fqn` and attaches matching rows to:

| Trace | Enriched node |
|-------|----------------|
| `GET /v1/graph/event-flow` | First `EventFlowStep.inboundTriggers[]` |
| `GET /v1/graph/event-flow/cross-repo` | First subscriber of hop 1 — `PubSubOrgView.inboundTriggers[]` |

Lookup is **enrich-only** — no new graph edges or endpoints. Reuses `EntryFlowService.triggersForHandler()` (shared with [TRG-13](20-trg-13-reverse-impact.md) reverse impact API).

## API shape

```json
{
  "steps": [{
    "order": 1,
    "handler": "com.example.RiqOfferEventConsumer",
    "inboundTriggers": [{
      "triggerId": "pubsub:pdn_s.riq_offer_event:...",
      "triggerKind": "PUBSUB_SUBSCRIBE",
      "pathPattern": "PDN_S.RIQ_OFFER_EVENT",
      "linkedHandlerFqn": "com.example.RiqOfferEventConsumer"
    }]
  }]
}
```

## Code

| File | Role |
|------|------|
| `EntryFlowService.triggersForHandler` | JDBC reverse lookup on `linked_handler_fqn` |
| `EventFlowFirstHopEnricher` | Applies enrichment to single-service and cross-repo traces |
| `MessagingFlowService.traceTopicFlow` / `traceCrossRepo` | Wiring after consistency hint enrichment |

## Tests

- `EventFlowFirstHopEnricherTest` — unit
- `MessagingFlowIntegrationTest` — seeded `PUBSUB_SUBSCRIBE` trigger on subscriber handler

## Non-goals

- Does not merge entry-flow and event-flow into one endpoint
- Does not prove runtime Pub/Sub delivery (static index only)
