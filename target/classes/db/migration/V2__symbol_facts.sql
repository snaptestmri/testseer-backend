CREATE TABLE symbol_facts (
    id                   BIGSERIAL    PRIMARY KEY,
    org_id               VARCHAR(100) NOT NULL,
    repo                 VARCHAR(255) NOT NULL,
    service_id           VARCHAR(255) NOT NULL REFERENCES service_registry(service_id),
    commit_sha           VARCHAR(40)  NOT NULL,
    file_path            VARCHAR(500) NOT NULL,
    symbol_fqn           VARCHAR(500) NOT NULL,
    symbol_kind          VARCHAR(50)  NOT NULL,
    snapshot_type        VARCHAR(10)  NOT NULL,
    attributes           JSONB,
    evidence_source      VARCHAR(50)  NOT NULL,
    confidence           FLOAT        NOT NULL DEFAULT 1.0,
    fact_schema_version  VARCHAR(10)  NOT NULL DEFAULT '1.0',
    indexed_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_symbol_facts_service ON symbol_facts(org_id, service_id, commit_sha);
CREATE INDEX idx_symbol_facts_fqn     ON symbol_facts(symbol_fqn);
