CREATE TABLE peripheral_facts (
    id                BIGSERIAL    PRIMARY KEY,
    org_id            VARCHAR(100) NOT NULL,
    service_id        VARCHAR(255) NOT NULL REFERENCES service_registry(service_id),
    commit_sha        VARCHAR(40)  NOT NULL,
    peripheral_type   VARCHAR(100) NOT NULL,
    detection_tier    SMALLINT     NOT NULL CHECK (detection_tier IN (1, 2, 3)),
    detection_signals JSONB        NOT NULL,
    prerequisite_text TEXT         NOT NULL,
    reason_code       VARCHAR(100),
    indexed_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_peripheral_service ON peripheral_facts(org_id, service_id);

CREATE TABLE unsupported_construct_facts (
    id          BIGSERIAL    PRIMARY KEY,
    org_id      VARCHAR(100) NOT NULL,
    service_id  VARCHAR(255) NOT NULL REFERENCES service_registry(service_id),
    commit_sha  VARCHAR(40)  NOT NULL,
    file_path   VARCHAR(500) NOT NULL,
    reason_code VARCHAR(100) NOT NULL,
    detail      TEXT,
    indexed_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
