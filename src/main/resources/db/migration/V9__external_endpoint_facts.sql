-- V9: External / partner HTTP endpoint facts (Phases A–D)
-- Complements outbound_call_facts (internal path matching) with config-resolved URLs.

CREATE TABLE external_endpoint_facts (
    id              BIGSERIAL    PRIMARY KEY,
    org_id          VARCHAR(100) NOT NULL,
    repo            VARCHAR(255) NOT NULL,
    service_id      VARCHAR(255) NOT NULL REFERENCES service_registry(service_id),
    commit_sha      VARCHAR(40)  NOT NULL,
    snapshot_type   VARCHAR(10)  NOT NULL,
    endpoint_id     VARCHAR(200) NOT NULL,
    partner_slug    VARCHAR(100),
    operation       VARCHAR(100),
    http_method     VARCHAR(10),
    url_template    VARCHAR(1000),
    url_resolved    VARCHAR(1000),
    env_lane        VARCHAR(20)  NOT NULL DEFAULT 'unknown',
    boundary        VARCHAR(20)  NOT NULL DEFAULT 'EXTERNAL',
    config_key      VARCHAR(500),
    yaml_path       VARCHAR(500),
    caller_class_fqn VARCHAR(500),
    client_class_fqn VARCHAR(500),
    flow_step       VARCHAR(100),
    auth_scheme     VARCHAR(100),
    evidence_source VARCHAR(50)  NOT NULL,
    confidence      FLOAT        NOT NULL DEFAULT 0.85,
    attributes      JSONB,
    indexed_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_external_endpoint
    ON external_endpoint_facts (
        service_id, commit_sha, endpoint_id, env_lane,
        COALESCE(config_key, ''), COALESCE(url_resolved, '')
    );

CREATE INDEX idx_external_endpoint_service ON external_endpoint_facts (service_id, env_lane);
CREATE INDEX idx_external_endpoint_partner ON external_endpoint_facts (org_id, partner_slug, env_lane);
CREATE INDEX idx_external_endpoint_flow     ON external_endpoint_facts (service_id, flow_step);

CREATE TABLE external_call_site_facts (
    id               BIGSERIAL    PRIMARY KEY,
    org_id           VARCHAR(100) NOT NULL,
    repo             VARCHAR(255) NOT NULL,
    service_id       VARCHAR(255) NOT NULL REFERENCES service_registry(service_id),
    commit_sha       VARCHAR(40)  NOT NULL,
    snapshot_type    VARCHAR(10)  NOT NULL,
    source_symbol    VARCHAR(500) NOT NULL,
    config_accessor  VARCHAR(200),
    config_prefix    VARCHAR(300),
    config_property  VARCHAR(200),
    http_client_type VARCHAR(50),
    http_client_method VARCHAR(50),
    http_method      VARCHAR(10),
    endpoint_id      VARCHAR(200),
    evidence_source  VARCHAR(50)  NOT NULL,
    confidence       FLOAT        NOT NULL DEFAULT 0.80,
    indexed_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_external_call_site
    ON external_call_site_facts (
        service_id, commit_sha, source_symbol,
        COALESCE(config_property, ''), COALESCE(http_client_method, '')
    );

CREATE INDEX idx_external_call_site_service ON external_call_site_facts (service_id);
CREATE INDEX idx_external_call_site_endpoint ON external_call_site_facts (service_id, endpoint_id);
