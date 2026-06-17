ALTER TABLE maven_dependency_facts
    ADD COLUMN IF NOT EXISTS link_source VARCHAR(50),
    ADD COLUMN IF NOT EXISTS cross_repo BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_maven_dep_gav_linked
    ON maven_dependency_facts (to_group_id, to_artifact_id)
    WHERE linked_service_id IS NOT NULL;
