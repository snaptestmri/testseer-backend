-- V16: Partner API contract facts from riq-platform-apis-optimus OpenAPI specs (BL-046)

CREATE TABLE contract_operation_facts (
    id                      BIGSERIAL    PRIMARY KEY,
    org_id                  VARCHAR(100) NOT NULL,
    repo                    VARCHAR(255) NOT NULL,
    service_id              VARCHAR(255) NOT NULL REFERENCES service_registry(service_id),
    commit_sha              VARCHAR(40)  NOT NULL,
    snapshot_type           VARCHAR(10)  NOT NULL,
    operation_id            VARCHAR(256) NOT NULL,
    spec_domain             VARCHAR(128) NOT NULL,
    spec_file               VARCHAR(512) NOT NULL,
    openapi_version         VARCHAR(16),
    operation_id_openapi    VARCHAR(128),
    http_method             VARCHAR(16)  NOT NULL,
    path_template           VARCHAR(512) NOT NULL,
    path_normalized         VARCHAR(512),
    summary                 TEXT,
    tags                    JSONB,
    request_schema_ref      VARCHAR(512),
    response_schema_ref     VARCHAR(512),
    request_field_summary   JSONB,
    response_field_summary  JSONB,
    server_urls             JSONB,
    mapped_service_name     VARCHAR(255),
    evidence_source         VARCHAR(32)  NOT NULL DEFAULT 'OPENAPI',
    confidence              FLOAT        NOT NULL DEFAULT 0.95,
    attributes              JSONB,
    indexed_at              TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_contract_operation
    ON contract_operation_facts (service_id, commit_sha, operation_id);

CREATE INDEX idx_contract_op_domain ON contract_operation_facts (org_id, spec_domain);
CREATE INDEX idx_contract_op_mapped ON contract_operation_facts (org_id, mapped_service_name);

CREATE TABLE contract_schema_facts (
    id                  BIGSERIAL    PRIMARY KEY,
    org_id              VARCHAR(100) NOT NULL,
    repo                VARCHAR(255) NOT NULL,
    service_id          VARCHAR(255) NOT NULL REFERENCES service_registry(service_id),
    commit_sha          VARCHAR(40)  NOT NULL,
    schema_id           VARCHAR(512) NOT NULL,
    schema_title        VARCHAR(256),
    schema_type         VARCHAR(32),
    top_level_fields    JSONB,
    required_fields     JSONB,
    spec_file           VARCHAR(512),
    evidence_source     VARCHAR(32)  NOT NULL DEFAULT 'JSON_SCHEMA',
    indexed_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_contract_schema
    ON contract_schema_facts (service_id, commit_sha, schema_id);

CREATE INDEX idx_contract_schema_id ON contract_schema_facts (org_id, schema_id);
