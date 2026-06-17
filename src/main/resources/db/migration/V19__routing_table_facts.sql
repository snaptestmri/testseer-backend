CREATE TABLE routing_table_facts (
    org_id              VARCHAR(100)  NOT NULL,
    repo                VARCHAR(255)  NOT NULL,
    service_id          VARCHAR(100)  NOT NULL,
    commit_sha          VARCHAR(64)   NOT NULL,
    snapshot_type       VARCHAR(50)   NOT NULL DEFAULT 'BASELINE',
    factory_class_fqn   VARCHAR(500)  NOT NULL,
    selector_method     VARCHAR(200),
    discriminator_type  VARCHAR(500),
    routing_key         VARCHAR(200)  NOT NULL,
    target_bean         VARCHAR(200),
    target_class_fqn    VARCHAR(500)  NOT NULL,
    fallback            BOOLEAN       NOT NULL DEFAULT FALSE,
    evidence_source     VARCHAR(50),
    confidence          FLOAT         NOT NULL DEFAULT 0.95
);

CREATE INDEX idx_routing_factory ON routing_table_facts(service_id, factory_class_fqn);
CREATE INDEX idx_routing_target  ON routing_table_facts(service_id, target_class_fqn);
