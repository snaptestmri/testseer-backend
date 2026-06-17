# Option C — Messaging Flow Inventory

> **E2E design doc:** [features/07-option-c-messaging-flow.md](features/07-option-c-messaging-flow.md)  
> **Live GCP verify (MSG-10):** [features/19-live-pubsub-verify.md](features/19-live-pubsub-verify.md)  
> **Architecture:** [TestSeer_Phase1_Architecture.md](TestSeer_Phase1_Architecture.md)

TestSeer Option C indexes Pub/Sub topology, message schemas, DB touchpoints, validation hints, and flow gates from repo YAML + Java.

## Phases implemented

| Phase | API | MCP tool |
|-------|-----|----------|
| C-P1 Pub/Sub inventory | `GET /v1/facts/pubsub` | `testseer_get_pubsub_inventory` |
| C-P2 Message schemas | `GET /v1/facts/message-schema` (`payloadProto`, `payloadFields`, `unpackExpression`) | (via `testseer_trace_topic_flow`); Event Flow viz renders `payloadFields` table — [22-event-flow-viz-redesign.md](features/22-event-flow-viz-redesign.md) P4.1 |
| C-P3 Data access | `GET /v1/facts/data-access` | (via trace) |
| C-P4 Validation hints | `GET /v1/facts/validation-hints` | (via trace) |
| C-P5 Messaging gaps | `GET /v1/gaps/messaging` | (via trace `gaps` field) |
| C-P6 Flow gates | `GET /v1/facts/gates` | `testseer_get_flow_gates` |
| Cross-repo trace | `GET /v1/graph/event-flow/cross-repo` | `testseer_trace_topic_flow` with `crossRepo=true` |
| Live GCP verify (MSG-10) | `?liveVerify=true` on trace + inventory; MCP `liveVerify` param | `testseer_get_pubsub_inventory`, `testseer_trace_topic_flow` |
| Clear index | `POST /admin/index/clear` | `testseer_clear_index` |

## Freshness and errors (P16)

All service-scoped messaging fact and graph endpoints follow the same freshness HTTP rules as `/v1/facts/class`:

| `freshnessStatus` | HTTP |
|-------------------|------|
| `NOT_INDEXED` | **404** |
| `INDEXING` | **202** |
| `CURRENT` / `STALE` | **200** |

If a repo was not indexed, trace calls return **404** with `NOT_INDEXED` — run `index-all-repos.sh` first.

## Cross-repo linking

Offer events span **multiple Git repos**. TestSeer indexes each repo as a separate `service_id` in `service_registry`. Cross-repo linking does **not** merge repos at index time — it **joins at query time** using shared keys.

### Join keys

| Key | Links |
|-----|--------|
| `short_id` + `env_lane` | Same topic name in different services (e.g. `PDN_T.OFFER_UPDATE@pdn`) |
| Topic stem heuristic | `PDN_T.OFFER_UPDATE` → subscriptions matching `*OFFER_UPDATE*` (e.g. `PDN_S.OFFER_UPDATE.PARTNER_NOTIFY`) |
| `payload_proto` FQN | Java `.unpack(RIQOfferEvent.Offer.class)` in consumer + proto in msg-framework |
| `linked_class_fqn` | Publisher/consumer class within a module (or cross-module via tier-2 linker) |

### Multi-module linkage (partner-adapter example)

`riq-partner-adapter-suite` is one `service_id` with three source roots (`partner-adapter-lib`, `partner-adapter-consumer`, `partner-adapter-ns`). Pub/sub YAML in the consumer module can link to Java in the lib module:

| YAML topic/sub | Typical linked class | Notes |
|----------------|---------------------|-------|
| `PDN_T.UMO_EVENT` PUBLISH | `FreedomOfferUpdateEventPublisher` (lib) | Tier-2 service-wide search or `pubSubClassLinks.springKeyLeaf: freedomumo` |
| `PDN_S.PARTNER_ADAPTER_NOTIFICATION` SUBSCRIBE | `PartnerAdapterConsumer` (consumer) | Tier-1 module match |
| `PDN_T.PARTNER_ADAPTER_NOTIFICATION` PUBLISH | `PartnerAdapterPublishService` (consumer/ns) | `*PublishService` + `PubSubMsgGateway` heuristic |

Re-index a service module after linker changes:

```bash
./scripts/clear-index.sh SERVICE <service-id>   # optional if same commit re-run
curl -X POST http://localhost:8080/admin/index/local \
  -H 'Content-Type: application/json' \
  -d '{"orgId":"quotient","path":"/path/to/riq-partner-adapter-suite","serviceModuleId":"partner-adapter-suite"}'
```

Pub/sub facts for the commit are replaced on each index write (delete-by-commit then insert).

### Offer-event bundle (minimum repos to index)

| Repo | Role in flow | What TestSeer extracts |
|------|--------------|------------------------|
| `optimus-platform-msg-framework` | Shared proto catalog | `RIQOfferEvent.Offer`, `OfferUpdates.OfferUpdateEvent`, `UMOEvents.UMOEvent`, `QMsgEvent` |
| `optimus-offer-services-suite` | Core pipeline | Yaml topics/subs, publishers, consumers, Freedom/CPA gates |
| `riq-partner-adapter-suite` | Hyvee adapter | `InsertedBy=FREEDOM` gate, `PartnerOfferCallRecorder` DB writes |
| `platform-argocd-manifest` | Deployment names | Workload names, optional full GCP URIs |

Index workspace repos:

```bash
./scripts/index-all-repos.sh quotient http://localhost:8080
```

For a clean re-index:

```bash
./scripts/clear-index.sh ORG quotient
./scripts/index-all-repos.sh quotient http://localhost:8080
```

### Cross-repo trace API

```bash
curl 'http://localhost:8080/v1/graph/event-flow/cross-repo?orgId=quotient&shortId=PDN_T.RIQ_OFFER_EVENT&env=pdn'
```

Response structure:

```json
{
  "data": {
    "orgId": "quotient",
    "startTopic": "PDN_T.RIQ_OFFER_EVENT",
    "hops": [
      {
        "order": 1,
        "topicShortId": "PDN_T.RIQ_OFFER_EVENT",
        "publishers": [{ "repo": "optimus-offer-services-suite", "workloadName": "offer-ingestion-ns", ... }],
        "subscribers": [{ "repo": "optimus-offer-services-suite", "workloadName": "offer-events-consumer-ns", ... }]
      },
      {
        "order": 2,
        "topicShortId": "PDN_T.OFFER_UPDATE",
        "publishers": [...],
        "subscribers": [...]
      }
    ],
    "missingBundleRepos": ["platform-argocd-manifest"],
    "gaps": []
  }
}
```

BFS walks: subscriber service → topics that service publishes → next hop.

### MCP cross-repo trace

```
testseer_trace_topic_flow({
  crossRepo: true,
  orgId: "quotient",
  shortId: "PDN_T.RIQ_OFFER_EVENT",
  env: "pdn"
})
```

Single-repo trace (deeper schema/DB/gates for one monorepo):

```
testseer_trace_topic_flow({
  serviceId: "optimus-offer-services-suite",
  shortId: "PDN_T.OFFER_UPDATE",
  env: "pdn"
})
```

### Cross-repo consistency hints (shipped)

- **`consistencyHints[]`** on each **publisher and subscriber** hop and deduplicated on `CrossRepoFlowReport` root — dual-write, mirror, and rule-pack scenarios (S-03/S-04 on Hy-Vee hop via delegate touchpoint expansion)
- See [12-data-consistency-hints.md](features/12-data-consistency-hints.md) for matcher and verification curl

### What cross-repo linking does NOT do yet

- **Subscription → topic GCP attach** — requires P3 GCP reconcile or explicit yaml mapping
- **Runtime proof** — static inventory only; use tests/logs for delivery proof
- **External CPA notifier repo** — if source not cloned/indexed, hop shows `NO_SUBSCRIBER` gap
- **Maven dependency graph** — Java `import` across jars is not the same as Pub/Sub hop (use impact analysis for code deps)

## Index a local monorepo

```bash
curl -X POST http://localhost:8080/admin/index/local \
  -H 'Content-Type: application/json' \
  -d '{"orgId":"quotient","path":"/path/to/optimus-offer-services-suite"}'
```

Indexes:
- All `**/*.java` under the repo
- `application*.yaml` / `.yml` under `src/main/resources`
- `**/*.proto` under resources

## Clear indexed facts (re-index cleanly)

Remove stale facts before re-indexing so cross-repo traces don't mix old and new data.

**Pub/sub re-index (same commit):** `DualWriteService` replaces all `pubsub_resource_facts` for `(service_id, commit_sha)` on each index run — no manual clear needed for linker/class updates. Requires Flyway **V21** when multiple HTTP notification publisher classes index against the same topic yaml (e.g. `quotient/transaction-eval-suite` → `ReceiptTxnEvalProcessor` + `CorrectedTxnEvalProcessor` on `DEV_T.NOTIFICATION_REQ`; see [07-option-c-messaging-flow.md](features/07-option-c-messaging-flow.md)).

| Scope | Deletes |
|-------|---------|
| `SERVICE` | All facts, graph edges/nodes, and Mongo parsed models for one `serviceId` |
| `MESSAGING` | Option C tables only (pubsub, schema, data-access, gates, validation hints) |
| `ORG` | All facts + graph for an org; optional `includeRegistry=true` to wipe `service_registry` |

```bash
chmod +x scripts/clear-index.sh

# One service
./scripts/clear-index.sh SERVICE optimus-offer-services-suite

# Option C facts only
./scripts/clear-index.sh MESSAGING optimus-offer-services-suite

# Entire org (keeps service_registry rows)
./scripts/clear-index.sh ORG quotient

# Full reset including registry
./scripts/clear-index.sh ORG quotient --include-registry
```

API:

```bash
curl -X POST http://localhost:8080/admin/index/clear \
  -H 'Content-Type: application/json' \
  -d '{"scope":"SERVICE","serviceId":"optimus-offer-services-suite"}'

# Shortcut
curl -X DELETE http://localhost:8080/admin/index/optimus-offer-services-suite
```

Bundle re-index with clear first:

```bash
./scripts/clear-index.sh ORG quotient
./scripts/index-all-repos.sh quotient http://localhost:8080
```

MCP: `testseer_clear_index({ scope: "ORG", orgId: "quotient" })`

## Query examples

```bash
# Pub/Sub facts for PDN (single service)
curl 'http://localhost:8080/v1/facts/pubsub?serviceId=optimus-offer-services-suite&env=pdn'

# Single-service deep trace
curl 'http://localhost:8080/v1/graph/event-flow?serviceId=optimus-offer-services-suite&shortId=PDN_T.OFFER_UPDATE&env=pdn'

# Cross-repo platform trace
curl 'http://localhost:8080/v1/graph/event-flow/cross-repo?orgId=quotient&shortId=PDN_T.RIQ_OFFER_EVENT&env=pdn'

# Cross-repo trace with live GCP Pub/Sub verify (MSG-10; requires PUBSUB_LIVE_VERIFY=true + credentials)
curl 'http://localhost:8080/v1/graph/event-flow/cross-repo?orgId=quotient&shortId=PDN_T.RIQ_OFFER_EVENT&env=pdn&liveVerify=true'

# Gates for Hyvee adapter path
curl 'http://localhost:8080/v1/facts/gates?serviceId=riq-partner-adapter-suite&flowStep=HYVEE_ADAPTER&env=pdn'
```

## Database tables (V8 migration)

- `pubsub_resource_facts`
- `message_schema_facts`
- `data_access_facts`
- `flow_gate_facts`
- `validation_hint_facts`
- `pubsub_verification_facts` (BL-015 index reconcile; live GCP fields when `PUBSUB_LIVE_VERIFY=true` — see [features/19-live-pubsub-verify.md](features/19-live-pubsub-verify.md))

## Graph edge types

- `PUBLISHES_TO`, `SUBSCRIBES_TO`, `GUARDED_BY`

## Limitations

- GitHub PR indexing only picks up config files present in the PR diff (use local index for full yaml scan).
- Proto/Java linkage uses `.unpack()` / `Any.pack()` heuristics.
- GCP live verify (MSG-10) is opt-in via `?liveVerify=true` on cross-repo trace and pub/sub inventory (`/v1/facts/pubsub`, `/v1/facts/pubsub/org`); requires `PUBSUB_LIVE_VERIFY=true` + GCP credentials.
- Live `system_configuration` snapshot (MSG-11) is shipped — see [features/15-live-flow-gates.md](features/15-live-flow-gates.md).
