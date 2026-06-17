-- V11: Inbound entry trigger facts (REST/webhook ingress, future: GCS/cron/Airflow)

CREATE TABLE entry_trigger_facts (
    id                  BIGSERIAL    PRIMARY KEY,
    org_id              VARCHAR(100) NOT NULL,
    repo                VARCHAR(255) NOT NULL,
    service_id          VARCHAR(255) NOT NULL REFERENCES service_registry(service_id),
    commit_sha          VARCHAR(40)  NOT NULL,
    snapshot_type       VARCHAR(10)  NOT NULL,
    trigger_id          VARCHAR(200) NOT NULL,
    trigger_kind        VARCHAR(30)  NOT NULL,
    direction           VARCHAR(10)  NOT NULL DEFAULT 'INBOUND',
    env_lane            VARCHAR(20)  NOT NULL DEFAULT 'unknown',
    actor               VARCHAR(100),
    boundary            VARCHAR(20)  NOT NULL DEFAULT 'EXTERNAL',
    http_method         VARCHAR(10),
    path_pattern        VARCHAR(500),
    linked_handler_fqn  VARCHAR(500),
    linked_method       VARCHAR(200),
    flow_step           VARCHAR(100),
    source_ref          VARCHAR(500),
    evidence_source     VARCHAR(50)  NOT NULL,
    confidence          FLOAT        NOT NULL DEFAULT 0.85,
    attributes          JSONB,
    indexed_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_entry_trigger
    ON entry_trigger_facts (
        service_id, commit_sha, trigger_id, env_lane,
        COALESCE(http_method, ''), COALESCE(path_pattern, '')
    );

CREATE INDEX idx_entry_trigger_service ON entry_trigger_facts (service_id, env_lane);
CREATE INDEX idx_entry_trigger_kind     ON entry_trigger_facts (service_id, trigger_kind);
CREATE INDEX idx_entry_trigger_actor    ON entry_trigger_facts (org_id, actor, env_lane);
CREATE INDEX idx_entry_trigger_handler  ON entry_trigger_facts (service_id, linked_handler_fqn);
