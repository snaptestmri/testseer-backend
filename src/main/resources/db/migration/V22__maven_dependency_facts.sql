CREATE TABLE maven_module_facts (
    org_id              VARCHAR(100)  NOT NULL,
    repo                VARCHAR(255)  NOT NULL,
    service_id          VARCHAR(100)  NOT NULL,
    commit_sha          VARCHAR(64)   NOT NULL,
    module_path         VARCHAR(500)  NOT NULL,
    relative_pom_path   VARCHAR(500)  NOT NULL,
    group_id            VARCHAR(200),
    artifact_id         VARCHAR(200),
    version             VARCHAR(100),
    packaging           VARCHAR(50),
    parent_group_id     VARCHAR(200),
    parent_artifact_id  VARCHAR(200),
    parent_version      VARCHAR(100),
    is_root_module      BOOLEAN       NOT NULL DEFAULT FALSE,
    resolution_status   VARCHAR(50)   NOT NULL DEFAULT 'DECLARED_ONLY',
    evidence_source     VARCHAR(50)   NOT NULL DEFAULT 'pom-xml',
    indexed_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (service_id, commit_sha, module_path)
);

CREATE INDEX idx_maven_module_service ON maven_module_facts(service_id, commit_sha);

CREATE TABLE maven_dependency_facts (
    org_id              VARCHAR(100)  NOT NULL,
    repo                VARCHAR(255)  NOT NULL,
    service_id          VARCHAR(100)  NOT NULL,
    commit_sha          VARCHAR(64)   NOT NULL,
    from_module_path    VARCHAR(500)  NOT NULL,
    to_group_id         VARCHAR(200)  NOT NULL,
    to_artifact_id      VARCHAR(200)  NOT NULL,
    to_version          VARCHAR(100),
    version_literal     VARCHAR(200),
    scope               VARCHAR(50)   NOT NULL DEFAULT 'compile',
    optional            BOOLEAN       NOT NULL DEFAULT FALSE,
    transitive          BOOLEAN       NOT NULL DEFAULT FALSE,
    resolved            BOOLEAN       NOT NULL DEFAULT FALSE,
    unresolved_reason   VARCHAR(200),
    linked_service_id   VARCHAR(100),
    linked_repo         VARCHAR(255),
    evidence_source     VARCHAR(50)   NOT NULL DEFAULT 'pom-xml',
    confidence          FLOAT         NOT NULL DEFAULT 0.95,
    indexed_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (service_id, commit_sha, from_module_path, to_group_id, to_artifact_id, scope, transitive, version_literal)
);

CREATE INDEX idx_maven_dep_artifact ON maven_dependency_facts(to_group_id, to_artifact_id, to_version);
CREATE INDEX idx_maven_dep_from ON maven_dependency_facts(service_id, from_module_path);
CREATE INDEX idx_maven_dep_linked ON maven_dependency_facts(linked_service_id);
