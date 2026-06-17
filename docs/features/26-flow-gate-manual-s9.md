# Feature: FlowGateExtractor — Manual §9 partner/system config gates (BL-052)

> **Status:** Shipped (BL-052)  
> **Backlog:** [BL-052](../../../docs/BACKLOG.md)  
> **Full design:** [TestSeer_BL051_FlowGate_Manual_S9_Design.md](../TestSeer_BL051_FlowGate_Manual_S9_Design.md) (canonical)  
> **Extends:** [15-live-flow-gates.md](15-live-flow-gates.md) (static extraction) · [07-option-c-messaging-flow.md](07-option-c-messaging-flow.md) C-P6  
> **Evidence:** `DesignDocuments/Docs/TransactionEvalConsumer_ServiceGraph_GapAnalysis.md` §6

## Problem

Before BL-052, `FlowGateExtractor` indexed **AST-local guards** (`IsDiscounted`, `if (!flag)`, `@Value`) but missed Quotient **`ConfigService` / `SystemConfigKeys`** idioms and class-level `@ConditionalOnProperty` when `ParsedModel` stored annotation names only.

On `transaction-eval-suite`, manual §9 lists ~11 behavior switches; TestSeer indexed **6 gates** on `transaction.eval` — expected pre-BL-052, not an index failure.

## What shipped

| Pillar | Extractor change | Example `gateKey` |
|--------|------------------|-------------------|
| **A** | `isConfigEnabled` / `config(..., SystemConfigKeys.X)` → `SYSTEM_CONFIG` | `SkipEvaluationEnabled`, `TrustedRedemptionEnabled` |
| **B** | `@ConditionalOnProperty` from Java source text | `kafka.topics.stxn.pipeline.enabled` |
| **C** | YAML gates via `YamlConfigUtils` ConfigMap unwrap | same kafka flag from `*.config-map.yaml` |
| **D** | Rule pack: `codeGateRules` (`gateKeyFromGroup`), `declaredGates`, `gateKeyAliases`, `classFlowStepRules` | fraud rules ternary, `CONDITIONAL_STACKING_OFFERIDS` |

**Dedup:** `TrustedRedemptionEnabled` `SYSTEM_CONFIG` replaces `isTrustedPartner=true` `CODE_FLAG`; `YAML_FLAG` dropped when `CONDITIONAL_BEAN` exists for same key.

## Rule pack (`quotient-messaging.yml`)

- `classFlowStepRules`: `transaction.eval` → `EVAL_STC`
- `codeGateRules`: `STCTransactionFraudRules` / `TransactionFraudRules` anchor
- `gateKeyAliases`: eval keys + `kafka.topics.stxn.pipeline.enabled` (for BL-027 live overlay)
- `declaredGates`: `ConditionalOfferStackingHelper` → `CONDITIONAL_STACKING_OFFERIDS`

## APIs (unchanged)

| Surface | Path / tool |
|---------|-------------|
| Static gates | `GET /v1/facts/gates?serviceId=...&env=...` |
| Event-flow preconditions | `GET /v1/graph/event-flow*` → `steps[].gates[]` |
| Live overlay | `LiveConfigSnapshotService` when `LIVE_CONFIG_ENABLED=true` (BL-027) |
| MCP | `testseer_get_flow_gates` |

## Acceptance (pilot — after re-index)

```bash
./scripts/index-all-repos.sh quotient http://localhost:8080

curl -s 'http://localhost:8080/v1/facts/gates?serviceId=0bab295f-1ce4-441e-a9ad-d29c547490d8&env=dev' \
  | jq '[.data[] | select(.guardedSymbolFqn | contains("transaction.eval")) | .gateKey] | unique'
```

Expect manual §9 keys plus existing AST gates (`IsDiscounted`, `stc-retry.max-retry-count`, etc.) — typically **~12–18** rows on `transaction.eval` (deduped).

## Tests

`FlowGateExtractorTest` — partner-scoped `SystemConfigKeys`, source `@ConditionalOnProperty`, ConfigMap yaml, fraud rule-pack anchor, `declaredGates`, TrustedRedemption dedup; §11.11 regressions unchanged.

## Related backlog

| ID | Relationship |
|----|----------------|
| BL-050 | Kafka ingress/egress (orthogonal); shares `YamlConfigUtils` |
| BL-051 | HTTP Pub/Sub notification hop (orthogonal) |
| BL-027 | `gateKeyAliases` enable live `liveStatus` for new keys |
