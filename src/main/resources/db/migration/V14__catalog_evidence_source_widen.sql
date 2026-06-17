-- V14: Widen catalog evidence_source — extractors chain tags (ENTITY_ANNOTATION+REPO_GENERIC+BQ_MIRROR+…)

ALTER TABLE data_object_facts
    ALTER COLUMN evidence_source TYPE VARCHAR(255);

ALTER TABLE accessor_method_facts
    ALTER COLUMN evidence_source TYPE VARCHAR(255);

ALTER TABLE schema_object_facts
    ALTER COLUMN evidence_source TYPE VARCHAR(255);
