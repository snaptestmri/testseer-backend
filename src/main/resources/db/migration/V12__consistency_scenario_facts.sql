-- V12: Data consistency hints — mirror + dual-write scenarios (CON Phase 1)
CREATE TABLE consistency_scenario_facts (
    id                  BIGSERIAL PRIMARY KEY,
    org_id              VARCHAR(100)  NOT NULL,
    repo                VARCHAR(255)  NOT NULL,
    service_id          VARCHAR(255)  NOT NULL REFERENCES service_registry(service_id),
    commit_sha          VARCHAR(40)   NOT NULL,
    snapshot_type       VARCHAR(10)   NOT NULL,
    scenario_id         VARCHAR(80)   NOT NULL,
    pattern             VARCHAR(40)   NOT NULL,
    scope_kind          VARCHAR(20)   NOT NULL,
    scope_ref           VARCHAR(500)  NOT NULL,
    primary_store       VARCHAR(20),
    primary_physical    VARCHAR(255),
    correlation_keys    JSONB,
    participants        JSONB         NOT NULL,
    poll_strategy       JSONB,
    invariants          JSONB,
    evidence_source     VARCHAR(80)   NOT NULL,
    confidence          FLOAT         NOT NULL,
    attributes          JSONB,
    indexed_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_consistency_scenario ON consistency_scenario_facts (
    service_id, commit_sha, scenario_id
);
CREATE INDEX idx_consistency_pattern ON consistency_scenario_facts(org_id, pattern);
CREATE INDEX idx_consistency_scope ON consistency_scenario_facts(scope_kind, scope_ref);
