-- Org-scoped workspace catalog configuration (CFG-CAT API)

CREATE TABLE workspace_org_settings (
    org_id         VARCHAR(100) PRIMARY KEY,
    github_dir     VARCHAR(512),
    default_bundle VARCHAR(100),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE workspace_catalog_library (
    org_id       VARCHAR(100) NOT NULL,
    library_id   VARCHAR(100) NOT NULL,
    repo         VARCHAR(255) NOT NULL,
    service_name VARCHAR(255),
    source_roots TEXT[]       NOT NULL DEFAULT '{"src/main/java"}',
    index_ddl    BOOLEAN      NOT NULL DEFAULT false,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (org_id, library_id)
);

CREATE TABLE workspace_service_module (
    org_id       VARCHAR(100) NOT NULL,
    module_id    VARCHAR(100) NOT NULL,
    repo         VARCHAR(255) NOT NULL,
    source_roots TEXT[]       NOT NULL DEFAULT '{"src/main/java"}',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (org_id, module_id)
);

CREATE TABLE workspace_symbol_classpath (
    org_id             VARCHAR(100) NOT NULL,
    module_id          VARCHAR(100) NOT NULL,
    catalog_library_id VARCHAR(100) NOT NULL,
    sort_order         INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (org_id, module_id, catalog_library_id),
    CONSTRAINT fk_symbol_classpath_module
        FOREIGN KEY (org_id, module_id)
        REFERENCES workspace_service_module (org_id, module_id) ON DELETE CASCADE,
    CONSTRAINT fk_symbol_classpath_library
        FOREIGN KEY (org_id, catalog_library_id)
        REFERENCES workspace_catalog_library (org_id, library_id) ON DELETE CASCADE
);

CREATE TABLE workspace_bundle (
    org_id         VARCHAR(100) NOT NULL,
    bundle_name    VARCHAR(100) NOT NULL,
    trace_short_id VARCHAR(255),
    trace_env      VARCHAR(50),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (org_id, bundle_name)
);

CREATE TABLE workspace_bundle_index_order (
    org_id             VARCHAR(100) NOT NULL,
    bundle_name        VARCHAR(100) NOT NULL,
    sort_order         INT          NOT NULL,
    catalog_library_id VARCHAR(100),
    service_module_id  VARCHAR(100),
    repo               VARCHAR(255),
    PRIMARY KEY (org_id, bundle_name, sort_order),
    CONSTRAINT fk_bundle_index_order_bundle
        FOREIGN KEY (org_id, bundle_name)
        REFERENCES workspace_bundle (org_id, bundle_name) ON DELETE CASCADE
);

CREATE INDEX idx_workspace_catalog_library_repo ON workspace_catalog_library (org_id, repo);
CREATE INDEX idx_workspace_service_module_repo ON workspace_service_module (org_id, repo);
