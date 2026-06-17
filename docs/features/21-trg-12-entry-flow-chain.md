# TRG-12 — Entry-flow chain: trigger → handler → Option C → outbound

> **Req:** [TRG-12](../../../docs/REQUIREMENTS.md) · **Goal:** [TRG-G03](../../../docs/REQUIREMENTS.md) · **Related:** [TRG-13](20-trg-13-reverse-impact.md) · [TRG-14](18-trg-14-first-hop-trigger-enrichment.md) · [Option C](07-option-c-messaging-flow.md)

## Problem

`GET /v1/graph/entry-flow` originally stopped at handler data access and flow gates. Test planning and journey visualization need the **forward chain** from an inbound trigger through messaging hops (Option C) and optional outbound partner HTTP.

## Solution

Extend `EntryFlowService.traceEntryFlow()` with opt-in flags that delegate messaging enrichment to `EntryFlowChainEnricher`:

```
GET /v1/graph/entry-flow?serviceId=&triggerId|path=&env=
  &includeMessaging=false&includeExternal=false&crossRepo=false&orgId=&maxHops=12
```

MCP: `testseer_trace_entry_flow` passes the same query flags as strings (`"true"`).

Default behavior is unchanged (`includeMessaging=false`, `includeExternal=false`, `crossRepo=false`).

## Response shape

`EntryFlowReport` adds optional chain fields (null/empty when flags off):

| Field | When set |
|-------|----------|
| `messagingTopicShortId` | Resolved topic used for Option C trace |
| `messagingFlow` | `EventFlowReport` from `MessagingFlowService.traceTopicFlow()` |
| `crossRepoFlow` | `CrossRepoFlowReport` when `crossRepo=true` |
| `externalEndpoints` | Handler-scoped outbound HTTP when `includeExternal=true` |

Existing `steps[]` (trigger + reads/writes/gates) are unchanged.

## Topic resolution

From the **first matched trigger** in `steps[0]`:

1. **Local Option C trace** (`traceTopicFlow`): `PUBSUB_SUBSCRIBE` uses subscription `pathPattern` as-is (`PDN_S.*`); publishers use `pubsub_resource_facts` `PUBLISH` row or topic short id.
2. **Cross-repo** (`traceCrossRepo`): canonical topic — map `PDN_S.*` → `PDN_T.*` when applicable.
3. **`messagingTopicShortId`**: canonical topic when mappable, else the local trace short id.

Env lane: request `env` → trigger `envLane` → `"unknown"`.

## Cross-repo

When `crossRepo=true` and `includeMessaging=true`, calls `MessagingFlowService.traceCrossRepo(orgId, topic, env, maxHops, …)`. `orgId` may be omitted — resolved from `service_registry` for `serviceId`.

## Implementation

| Component | Role |
|-----------|------|
| `EntryFlowChainEnricher` | Topic resolution + `traceTopicFlow` / `traceCrossRepo` / handler externals |
| `EntryFlowService` | Builds steps; calls enricher when flags set (`@Lazy` breaks cycle with `EventFlowFirstHopEnricher`) |
| `EntryTriggerQueryController` | Query params |
| MCP `testseer_trace_entry_flow` | Forwards flags |

`ContractEntryLinkService` continues to call the 4-arg overload (no chain enrichment).

## Tests

- `EntryFlowChainEnricherTest` — topic mapping, flag gating
- `MessagingFlowIntegrationTest.traceEntryFlow_withMessagingAndCrossRepo` — end-to-end with subscriber + publisher fixtures
- `MessagingFlowIntegrationTest.traceEntryFlow_defaultOmitsMessagingChain` — backward compatibility

## Non-goals

- Multi-trigger chain merging (only first step drives messaging topic)
- Runtime proof that triggers fired (TRG-NG01)
