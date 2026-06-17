-- V10: Data object catalog (WRK-18+) — entity/document catalog, accessor methods, schema DDL, enriched data access

CREATE TABLE data_object_facts (
    id                      BIGSERIAL PRIMARY KEY,
    org_id                  VARCHAR(100)  NOT NULL,
    repo                    VARCHAR(255)  NOT NULL,
    service_id              VARCHAR(255)  NOT NULL REFERENCES service_registry(service_id),
    commit_sha              VARCHAR(40)   NOT NULL,
    snapshot_type           VARCHAR(10)   NOT NULL,
    entity_fqn              VARCHAR(500)  NOT NULL,
    domain_fqn              VARCHAR(500),
    store_type              VARCHAR(20)   NOT NULL,
    physical_name           VARCHAR(255)  NOT NULL,
    catalog_or_keyspace     VARCHAR(100),
    collection_or_table_kind VARCHAR(20),
    evidence_source         VARCHAR(50)   NOT NULL,
    confidence              FLOAT         NOT NULL,
    attributes              JSONB,
    indexed_at              TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_data_object ON data_object_facts (
    service_id, commit_sha, entity_fqn, store_type, physical_name
);
CREATE INDEX idx_data_object_physical ON data_object_facts (store_type, physical_name);
CREATE INDEX idx_data_object_domain ON data_object_facts (domain_fqn);
CREATE INDEX idx_data_object_service ON data_object_facts (service_id);

CREATE TABLE accessor_method_facts (
    id                BIGSERIAL PRIMARY KEY,
    org_id            VARCHAR(100)  NOT NULL,
    repo              VARCHAR(255)  NOT NULL,
    service_id        VARCHAR(255)  NOT NULL REFERENCES service_registry(service_id),
    commit_sha        VARCHAR(40)   NOT NULL,
    snapshot_type     VARCHAR(10)   NOT NULL,
    accessor_kind     VARCHAR(20)   NOT NULL,
    accessor_fqn      VARCHAR(500)  NOT NULL,
    method_name       VARCHAR(255)  NOT NULL,
    operation         VARCHAR(10)   NOT NULL,
    entity_fqn        VARCHAR(500),
    domain_fqn        VARCHAR(500),
    store_type        VARCHAR(20)   NOT NULL,
    physical_name     VARCHAR(255),
    evidence_source   VARCHAR(50)   NOT NULL,
    confidence        FLOAT         NOT NULL,
    indexed_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_accessor_method ON accessor_method_facts (
    service_id, commit_sha, accessor_fqn, method_name
);
CREATE INDEX idx_accessor_method_entity ON accessor_method_facts (entity_fqn);

CREATE TABLE schema_object_facts (
    id                  BIGSERIAL PRIMARY KEY,
    org_id              VARCHAR(100)  NOT NULL,
    repo                VARCHAR(255)  NOT NULL,
    service_id          VARCHAR(255)  NOT NULL REFERENCES service_registry(service_id),
    commit_sha          VARCHAR(40)   NOT NULL,
    store_type          VARCHAR(20)   NOT NULL,
    physical_name       VARCHAR(255)  NOT NULL,
    catalog_or_keyspace VARCHAR(100),
    ddl_path            VARCHAR(500)  NOT NULL,
    evidence_source     VARCHAR(50)   NOT NULL DEFAULT 'DDL_FILE',
    indexed_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_schema_object ON schema_object_facts (
    service_id, commit_sha, store_type, physical_name,
    COALESCE(catalog_or_keyspace, '')
);

ALTER TABLE data_access_facts
    ADD COLUMN entity_fqn        VARCHAR(500),
    ADD COLUMN domain_fqn        VARCHAR(500),
    ADD COLUMN accessor_fqn      VARCHAR(500),
    ADD COLUMN accessor_kind     VARCHAR(20),
    ADD COLUMN catalog_ref       VARCHAR(100),
    ADD COLUMN secondary_stores  JSONB;
