# P14: GitHub PR Comment Bot

> **Status:** v1 **shipped** (2026-06-15). Canonical spec: [23-pr-comment-bot.md](../../features/23-pr-comment-bot.md)  
> **v2 (cross-repo fan-out):** BL-049 · PRB-20–25 in feature doc

**Goal:** When a PR opens or synchronizes, run impact analysis and post a formatted comment on the PR.

**Depends on:** P11 (`GET /v1/impact/pr`)

---

## Flow

```
GitHub webhook (pull_request opened/synchronize)
  → wait for indexing COMPLETE for head SHA
  → ImpactAnalysisService + GapDetectionService + ConsistencyGapService (in-process)
  → format comment
  → GitHub API POST/PATCH /repos/{owner}/{repo}/issues/{pr}/comments
```

---

## Comment format (target UX)

```
TestSeer Analysis for PR #47:

Changed: OrderController, PaymentClient
Affected services: billing-service (calls GET /orders/{id})
Suggested test scope:
  - OrderControllerTest (existing)
  - BillingIntegrationTest (existing, calls changed endpoint)
  - ⚠️ PaymentClient has no test class
```

Map from `ImpactReport`:
- `changedSymbols` → Changed line
- `affectedConsumers` → Affected services (may include **other repos** — display only in v1)
- `suggestedTestScope` with `exists: true|false` → test list with ⚠️ for gaps

---

## Requirements (summary)

See [PRB-01–PRB-12](../../features/23-pr-comment-bot.md#functional-requirements) in the feature doc.

---

## File structure (shipped)

```
testseer-backend/src/main/java/io/testseer/backend/github/
├── PrCommentFormatter.java
├── PrCommentPublisher.java
└── PrImpactCommentService.java
```

Hook: `WorkerPipeline` after `markComplete`.

---

## Out of scope (v1)

- Posting on consumer repos (v2 PRB-20)
- Companion PR linking (v2 PRB-21)
- Inline review comments on diff lines (v3 PRB-30)
