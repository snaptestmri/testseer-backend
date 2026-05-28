CREATE TABLE outbound_call_facts (
    id              BIGSERIAL    PRIMARY KEY,
    org_id          VARCHAR(100) NOT NULL,
    repo            VARCHAR(255) NOT NULL,
    service_id      VARCHAR(255) NOT NULL REFERENCES service_registry(service_id),
    commit_sha      VARCHAR(40)  NOT NULL,
    source_symbol   VARCHAR(500) NOT NULL,
    http_method     VARCHAR(10),
    path            VARCHAR(500),
    snapshot_type   VARCHAR(10)  NOT NULL,
    evidence_source VARCHAR(50)  NOT NULL,
    confidence      FLOAT        NOT NULL DEFAULT 1.0,
    indexed_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbound_service ON outbound_call_facts(org_id, service_id, commit_sha);
