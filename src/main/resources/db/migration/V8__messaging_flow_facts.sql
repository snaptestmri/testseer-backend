-- Option C: Pub/Sub inventory, message schemas, data access, gates, validation hints

CREATE TABLE pubsub_resource_facts (
    id                BIGSERIAL PRIMARY KEY,
    org_id            VARCHAR(100)  NOT NULL,
    repo              VARCHAR(255)  NOT NULL,
    service_id        VARCHAR(255)  NOT NULL REFERENCES service_registry(service_id),
    commit_sha        VARCHAR(40)   NOT NULL,
    snapshot_type     VARCHAR(10)   NOT NULL,
    resource_kind     VARCHAR(20)   NOT NULL,
    short_id          VARCHAR(255)  NOT NULL,
    env_lane          VARCHAR(32)   NOT NULL,
    env_profile       VARCHAR(64),
    gcp_project       VARCHAR(100),
    full_resource_id  VARCHAR(512),
    role              VARCHAR(20)   NOT NULL,
    spring_key        VARCHAR(255),
    yaml_path         VARCHAR(500)  NOT NULL,
    module_name       VARCHAR(255),
    linked_class_fqn  VARCHAR(500),
    linked_method     VARCHAR(255),
    workload_name     VARCHAR(255),
    evidence_source   VARCHAR(50)   NOT NULL,
    confidence        FLOAT         NOT NULL DEFAULT 1.0,
    attributes        JSONB,
    indexed_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_pubsub_resource ON pubsub_resource_facts (
    service_id, commit_sha, resource_kind, short_id, env_lane, role,
    COALESCE(spring_key, ''), yaml_path
);
CREATE INDEX idx_pubsub_service_env ON pubsub_resource_facts(service_id, env_lane);
CREATE INDEX idx_pubsub_short_id     ON pubsub_resource_facts(short_id, env_lane);
CREATE INDEX idx_pubsub_class        ON pubsub_resource_facts(linked_class_fqn);

CREATE TABLE message_schema_facts (
    id                BIGSERIAL PRIMARY KEY,
    org_id            VARCHAR(100)  NOT NULL,
    repo              VARCHAR(255)  NOT NULL,
    service_id        VARCHAR(255)  NOT NULL REFERENCES service_registry(service_id),
    commit_sha        VARCHAR(40)   NOT NULL,
    snapshot_type     VARCHAR(10)   NOT NULL,
    envelope_type     VARCHAR(100),
    payload_proto     VARCHAR(255)  NOT NULL,
    payload_fields    JSONB         NOT NULL DEFAULT '[]',
    payload_enums     JSONB,
    linked_class_fqn  VARCHAR(500),
    linked_method     VARCHAR(255),
    direction         VARCHAR(10)   NOT NULL,
    topic_short_id    VARCHAR(255),
    unpack_expression VARCHAR(500),
    proto_file        VARCHAR(500),
    evidence_source   VARCHAR(50)   NOT NULL,
    confidence        FLOAT         NOT NULL DEFAULT 1.0,
    indexed_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_message_schema ON message_schema_facts (
    service_id, commit_sha, payload_proto, direction,
    COALESCE(linked_class_fqn, ''), COALESCE(topic_short_id, '')
);
CREATE INDEX idx_message_schema_class ON message_schema_facts(linked_class_fqn);
CREATE INDEX idx_message_schema_topic   ON message_schema_facts(topic_short_id);

CREATE TABLE data_access_facts (
    id                BIGSERIAL PRIMARY KEY,
    org_id            VARCHAR(100)  NOT NULL,
    repo              VARCHAR(255)  NOT NULL,
    service_id        VARCHAR(255)  NOT NULL REFERENCES service_registry(service_id),
    commit_sha        VARCHAR(40)   NOT NULL,
    snapshot_type     VARCHAR(10)   NOT NULL,
    handler_class_fqn VARCHAR(500)  NOT NULL,
    handler_method    VARCHAR(255),
    operation         VARCHAR(10)   NOT NULL,
    store_type        VARCHAR(20)   NOT NULL DEFAULT 'MARIADB',
    table_or_entity   VARCHAR(255)  NOT NULL,
    repository_fqn    VARCHAR(500),
    dao_method        VARCHAR(255),
    correlation_keys  JSONB,
    validation_hint   JSONB,
    evidence_source   VARCHAR(50)   NOT NULL,
    confidence        FLOAT         NOT NULL DEFAULT 0.85,
    indexed_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_data_access ON data_access_facts (
    service_id, commit_sha, handler_class_fqn,
    COALESCE(handler_method, ''), operation, table_or_entity,
    COALESCE(dao_method, '')
);
CREATE INDEX idx_data_access_handler ON data_access_facts(handler_class_fqn);
CREATE INDEX idx_data_access_table     ON data_access_facts(table_or_entity);

CREATE TABLE flow_gate_facts (
    id                  BIGSERIAL PRIMARY KEY,
    org_id              VARCHAR(100)  NOT NULL,
    repo                VARCHAR(255)  NOT NULL,
    service_id          VARCHAR(255)  NOT NULL REFERENCES service_registry(service_id),
    commit_sha          VARCHAR(40)   NOT NULL,
    snapshot_type       VARCHAR(10)   NOT NULL,
    env_lane            VARCHAR(32)   NOT NULL DEFAULT 'unknown',
    guarded_symbol_fqn  VARCHAR(500)  NOT NULL,
    guarded_flow_step   VARCHAR(100),
    guarded_edge_type   VARCHAR(30),
    gate_kind           VARCHAR(30)   NOT NULL,
    gate_key            VARCHAR(255)  NOT NULL,
    required_value      VARCHAR(255),
    required_operator   VARCHAR(20)   NOT NULL DEFAULT 'EQ',
    effect_when_fail    VARCHAR(20)   NOT NULL,
    skip_log_pattern    VARCHAR(500),
    test_precondition   TEXT,
    evidence_source     VARCHAR(50)   NOT NULL,
    yaml_path           VARCHAR(500),
    confidence          FLOAT         NOT NULL DEFAULT 0.90,
    indexed_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_flow_gate ON flow_gate_facts (
    service_id, commit_sha, guarded_symbol_fqn, gate_key, env_lane
);
CREATE INDEX idx_flow_gate_symbol ON flow_gate_facts(guarded_symbol_fqn);
CREATE INDEX idx_flow_gate_step   ON flow_gate_facts(guarded_flow_step);

CREATE TABLE validation_hint_facts (
    id                BIGSERIAL PRIMARY KEY,
    org_id            VARCHAR(100)  NOT NULL,
    repo              VARCHAR(255)  NOT NULL,
    service_id        VARCHAR(255)  NOT NULL REFERENCES service_registry(service_id),
    commit_sha        VARCHAR(40)   NOT NULL,
    snapshot_type     VARCHAR(10)   NOT NULL,
    flow_step         VARCHAR(100)  NOT NULL,
    hint_kind         VARCHAR(30)   NOT NULL,
    hint_value        TEXT          NOT NULL,
    linked_symbol_fqn VARCHAR(500),
    env_lane          VARCHAR(32)   NOT NULL DEFAULT 'unknown',
    indexed_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_validation_hint ON validation_hint_facts (
    service_id, commit_sha, flow_step, hint_kind, env_lane,
    COALESCE(linked_symbol_fqn, '')
);

CREATE TABLE pubsub_verification_facts (
    id                  BIGSERIAL PRIMARY KEY,
    gcp_project         VARCHAR(100) NOT NULL,
    resource_kind       VARCHAR(20)  NOT NULL,
    short_id            VARCHAR(255) NOT NULL,
    full_resource_id    VARCHAR(512),
    exists_in_gcp       BOOLEAN      NOT NULL DEFAULT false,
    attached_topic      VARCHAR(512),
    verified_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    drift_status        VARCHAR(20),
    UNIQUE (gcp_project, resource_kind, short_id)
);
