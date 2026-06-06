# P14: GitHub PR Comment Bot (PLANNED)

> **Status:** Not implemented. Consumer-only — no new analysis logic.

**Goal:** When a PR opens or synchronizes, run impact analysis and post a formatted comment on the PR.

**Depends on:** P11 (`GET /v1/impact/pr`)

---

## Flow

```
GitHub webhook (pull_request opened/synchronize)
  → wait for indexing COMPLETE for head SHA
  → GET /v1/impact/pr?serviceId=&commitSha=
  → format comment
  → GitHub API POST /repos/{owner}/{repo}/issues/{pr}/comments
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
- `affectedConsumers` → Affected services
- `suggestedTestScope` with `exists: true|false` → test list with ⚠️ for gaps

---

## Requirements

- GitHub App with `pull_requests: write` and `contents: read`
- Reuse existing `WebhookController` PR ingestion path; add post-index callback
- Handle indexing-in-progress (202) — poll or defer comment until COMPLETE
- Idempotent: update existing TestSeer comment on re-sync rather than spamming

---

## File structure (planned)

```
testseer-backend/src/main/java/io/testseer/backend/
└── github/
    ├── PrCommentFormatter.java
    ├── PrCommentPublisher.java
    └── PrAnalysisOrchestrator.java   — triggers after WorkerPipeline complete for PR jobs
```

---

## Out of scope

- New analysis endpoints
- Inline review comments on diff lines (v2)
