-- BL-051: allow multiple HTTP_PUBSUB publish facts per topic when different handler classes
-- call the same notification API (e.g. ReceiptTxnEvalProcessor + CorrectedTxnEvalProcessor).
DROP INDEX IF EXISTS uq_pubsub_resource;
CREATE UNIQUE INDEX uq_pubsub_resource ON pubsub_resource_facts (
    service_id, commit_sha, resource_kind, short_id, env_lane, role,
    COALESCE(spring_key, ''), yaml_path, COALESCE(linked_class_fqn, '')
);
