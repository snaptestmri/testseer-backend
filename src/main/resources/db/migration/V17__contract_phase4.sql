-- V17: BL-046 Phase 4 — nested schema paths + REST-Assured test HTTP call facts

ALTER TABLE contract_schema_facts
    ADD COLUMN IF NOT EXISTS nested_field_paths JSONB;

CREATE TABLE test_http_call_facts (
    id                  BIGSERIAL    PRIMARY KEY,
    org_id              VARCHAR(100) NOT NULL,
    repo                VARCHAR(255) NOT NULL,
    service_id          VARCHAR(255) NOT NULL REFERENCES service_registry(service_id),
    commit_sha          VARCHAR(40)  NOT NULL,
    snapshot_type       VARCHAR(10)  NOT NULL,
    file_path           VARCHAR(512) NOT NULL,
    source_symbol       VARCHAR(512),
    http_method         VARCHAR(16)  NOT NULL,
    path                VARCHAR(512) NOT NULL,
    path_normalized     VARCHAR(512),
    path_constant_ref   VARCHAR(128),
    evidence_source     VARCHAR(32)  NOT NULL DEFAULT 'REST_ASSURED',
    confidence          FLOAT        NOT NULL DEFAULT 0.85,
    indexed_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_test_http_call
    ON test_http_call_facts (service_id, commit_sha, http_method, path_normalized, file_path);

CREATE INDEX idx_test_http_call_path ON test_http_call_facts (org_id, path_normalized);
CREATE INDEX idx_test_http_call_service ON test_http_call_facts (service_id, commit_sha);
