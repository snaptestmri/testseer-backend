CREATE TABLE service_registry (
    service_id   VARCHAR(255) PRIMARY KEY,
    org_id       VARCHAR(100) NOT NULL,
    repo         VARCHAR(255) NOT NULL,
    service_name VARCHAR(255) NOT NULL,
    module_type  VARCHAR(50)  NOT NULL DEFAULT 'service',
    build_tool   VARCHAR(50)  NOT NULL,
    source_roots TEXT[]       NOT NULL DEFAULT '{"src/main/java"}',
    test_roots   TEXT[]       NOT NULL DEFAULT '{"src/test/java"}',
    owner_team   VARCHAR(255),
    enabled      BOOLEAN      NOT NULL DEFAULT true,
    metadata     JSONB,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_service_registry UNIQUE (org_id, repo, service_name)
);
