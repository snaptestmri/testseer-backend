# P15: IntelliJ Plugin Impact Consumer (PLANNED)

> **Status:** Not implemented. Consumer-only — queries P11 API from IDE.

**Goal:** When editing a file in IntelliJ, show inline impact analysis from the central backend if the file is indexed.

**Depends on:** P11 (`GET /v1/impact/pr`), existing [`intellij-plugin/`](../../../intellij-plugin/)

---

## Flow

```
Developer opens/edits file in IntelliJ
  → read .testseer/config.yml (serviceId, apiBaseUrl, orgId)
  → resolve current git commit SHA
  → GET /v1/impact/pr?serviceId=&commitSha=
  → display in tool window or gutter annotation
```

---

## Config schema (TBD)

`.testseer/config.yml` — not yet implemented. Proposed fields:

```yaml
serviceId: "<registry UUID>"
apiBaseUrl: "http://localhost:8080"
orgId: acme
repo: orders
```

---

## Plugin changes (planned)

```
intellij-plugin/src/main/java/io/testseer/intellij/
├── config/TestSeerConfigLoader.java
├── api/ImpactAnalysisClient.java
└── ui/ImpactToolWindowFactory.java
```

Reuse existing `IntellijTestSeerFacade` wiring; add optional backend mode alongside local PSI path.

---

## UX

- Tool window panel: changed symbols, affected consumers, suggested tests
- Highlight missing tests (`exists: false`) with warning icon
- Fall back to local `TestSeerEngine.generatePlan()` when backend unreachable

---

## Out of scope

- `.testseer/config.yml` loader in backend (IDE-only for v1)
- Write actions (post comments, trigger re-index)
