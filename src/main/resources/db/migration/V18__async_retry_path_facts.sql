-- V18: BigQuery DLQ retry paths indexed from retry-job YAML (TRG-14+ terminal-hop linking)

CREATE TABLE async_retry_path_facts (
    id                  BIGSERIAL    PRIMARY KEY,
    org_id              VARCHAR(100) NOT NULL,
    repo                VARCHAR(255) NOT NULL,
    service_id          VARCHAR(255) NOT NULL REFERENCES service_registry(service_id),
    commit_sha          VARCHAR(40)  NOT NULL,
    snapshot_type       VARCHAR(10)  NOT NULL,
    env_lane            VARCHAR(20)  NOT NULL DEFAULT 'unknown',
    module_name         VARCHAR(200),
    linked_topic        VARCHAR(200),
    bq_dataset          VARCHAR(200) NOT NULL,
    bq_table            VARCHAR(200) NOT NULL,
    source_ref          VARCHAR(500),
    evidence_source     VARCHAR(50)  NOT NULL,
    confidence          FLOAT        NOT NULL DEFAULT 0.88,
    attributes          JSONB,
    indexed_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_async_retry_path
    ON async_retry_path_facts (
        service_id, commit_sha, env_lane, bq_dataset, bq_table,
        COALESCE(module_name, ''), COALESCE(linked_topic, '')
    );

CREATE INDEX idx_async_retry_path_org_env
    ON async_retry_path_facts (org_id, env_lane);

CREATE INDEX idx_async_retry_path_topic
    ON async_retry_path_facts (org_id, linked_topic, env_lane);
