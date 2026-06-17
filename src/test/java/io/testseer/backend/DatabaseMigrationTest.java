package io.testseer.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseMigrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcClient jdbcClient;

    @Test
    void serviceRegistryTableExists() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'service_registry'
                ORDER BY column_name
                """)
                .query(String.class)
                .list();

        assertThat(columns).contains(
                "service_id", "org_id", "repo", "service_name",
                "module_type", "build_tool", "source_roots", "test_roots",
                "owner_team", "enabled", "metadata", "created_at", "updated_at"
        );
    }

    @Test
    void symbolFactsTableExists() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'symbol_facts'
                ORDER BY column_name
                """)
                .query(String.class)
                .list();

        assertThat(columns).contains(
                "id", "org_id", "repo", "service_id", "commit_sha",
                "file_path", "symbol_fqn", "symbol_kind", "snapshot_type",
                "attributes", "evidence_source", "confidence",
                "fact_schema_version", "indexed_at"
        );
    }

    @Test
    void outboundCallFactsTableExists() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'outbound_call_facts'
                """)
                .query(String.class)
                .list();

        assertThat(columns).contains(
                "id", "org_id", "repo", "service_id", "commit_sha",
                "source_symbol", "http_method", "path",
                "snapshot_type", "evidence_source", "confidence", "indexed_at"
        );
    }

    @Test
    void peripheralFactsTableExists() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'peripheral_facts'
                """)
                .query(String.class)
                .list();

        assertThat(columns).contains(
                "id", "org_id", "service_id", "commit_sha",
                "peripheral_type", "detection_tier", "detection_signals",
                "prerequisite_text", "reason_code", "indexed_at"
        );
    }

    @Test
    void unsupportedConstructFactsTableExists() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'unsupported_construct_facts'
                """)
                .query(String.class)
                .list();

        assertThat(columns).contains(
                "id", "org_id", "service_id", "commit_sha",
                "file_path", "reason_code", "detail", "indexed_at"
        );
    }

    @Test
    void analysisRunsTableExists() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'analysis_runs'
                """)
                .query(String.class)
                .list();

        assertThat(columns).contains(
                "job_id", "org_id", "service_id", "commit_sha",
                "job_type", "status", "attempt",
                "enqueued_at", "started_at", "completed_at", "error_detail"
        );
    }

    @Test
    void graphNodesTableExists() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'graph_nodes'
                """)
                .query(String.class)
                .list();

        assertThat(columns).contains(
                "id", "org_id", "repo", "service", "module_type", "node_type", "symbol_fqn"
        );
    }

    @Test
    void graphNodeKeyColumnsAreWidened() {
        Integer idLen = jdbcClient.sql("""
                SELECT character_maximum_length FROM information_schema.columns
                WHERE table_name = 'graph_nodes' AND column_name = 'id'
                """)
                .query(Integer.class)
                .single();

        Integer symbolFqnLen = jdbcClient.sql("""
                SELECT character_maximum_length FROM information_schema.columns
                WHERE table_name = 'graph_nodes' AND column_name = 'symbol_fqn'
                """)
                .query(Integer.class)
                .single();

        assertThat(idLen).isGreaterThanOrEqualTo(1024);
        assertThat(symbolFqnLen).isGreaterThanOrEqualTo(2048);
    }

    @Test
    void graphEdgesTableExists() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'graph_edges'
                """)
                .query(String.class)
                .list();

        assertThat(columns).contains(
                "id", "from_node", "to_node", "edge_type", "confidence", "evidence_source"
        );
    }

    @Test
    void pubsubResourceFactsTableExists() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'pubsub_resource_facts'
                """)
                .query(String.class)
                .list();

        assertThat(columns).contains(
                "short_id", "env_lane", "resource_kind", "role",
                "linked_class_fqn", "workload_name", "evidence_source"
        );
    }

    @Test
    void flowGateFactsTableExists() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'flow_gate_facts'
                """)
                .query(String.class)
                .list();

        assertThat(columns).contains(
                "gate_kind", "gate_key", "guarded_symbol_fqn",
                "required_value", "effect_when_fail", "test_precondition"
        );
    }

    @Test
    void messageSchemaFactsTableExists() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'message_schema_facts'
                """)
                .query(String.class)
                .list();

        assertThat(columns).contains("payload_proto", "direction", "topic_short_id", "linked_class_fqn");
    }

    @Test
    void dataAccessFactsTableExists() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'data_access_facts'
                """)
                .query(String.class)
                .list();

        assertThat(columns).contains("handler_class_fqn", "operation", "table_or_entity");
    }

    @Test
    void validationHintFactsTableExists() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'validation_hint_facts'
                """)
                .query(String.class)
                .list();

        assertThat(columns).contains("flow_step", "hint_kind", "hint_value", "linked_symbol_fqn");
    }

    @Test
    void externalEndpointFactsTableExists() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'external_endpoint_facts'
                """)
                .query(String.class)
                .list();

        assertThat(columns).contains(
                "endpoint_id", "partner_slug", "operation", "url_resolved",
                "env_lane", "boundary", "config_key", "caller_class_fqn", "flow_step"
        );
    }

    @Test
    void externalCallSiteFactsTableExists() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'external_call_site_facts'
                """)
                .query(String.class)
                .list();

        assertThat(columns).contains(
                "source_symbol", "config_accessor", "config_property",
                "http_client_method", "endpoint_id"
        );
    }

    @Test
    void dataAccessFactsTableHasCatalogLinkageColumns() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'data_access_facts'
                """)
                .query(String.class)
                .list();

        assertThat(columns).contains(
                "entity_fqn", "domain_fqn", "accessor_fqn", "accessor_kind",
                "catalog_ref", "secondary_stores"
        );
    }

    @Test
    void dataObjectFactsTableExists() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'data_object_facts'
                """)
                .query(String.class)
                .list();

        assertThat(columns).contains(
                "entity_fqn", "domain_fqn", "store_type", "physical_name",
                "catalog_or_keyspace", "collection_or_table_kind", "attributes"
        );
    }

    @Test
    void catalogEvidenceSourceColumnsAreWideEnoughForChainedTags() {
        Integer minLength = jdbcClient.sql("""
                SELECT MIN(character_maximum_length) FROM information_schema.columns
                WHERE table_name IN ('data_object_facts', 'accessor_method_facts', 'schema_object_facts')
                  AND column_name = 'evidence_source'
                """)
                .query(Integer.class)
                .single();

        assertThat(minLength).isGreaterThanOrEqualTo(255);
    }

    @Test
    void accessorMethodFactsTableExists() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'accessor_method_facts'
                """)
                .query(String.class)
                .list();

        assertThat(columns).contains(
                "accessor_kind", "accessor_fqn", "method_name", "operation", "entity_fqn"
        );
    }

    @Test
    void schemaObjectFactsTableExists() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'schema_object_facts'
                """)
                .query(String.class)
                .list();

        assertThat(columns).contains(
                "store_type", "physical_name", "catalog_or_keyspace", "ddl_path"
        );
    }

    @Test
    void entryTriggerFactsTableExists() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'entry_trigger_facts'
                """)
                .query(String.class)
                .list();

        assertThat(columns).contains(
                "trigger_id", "trigger_kind", "direction", "env_lane", "actor", "boundary",
                "http_method", "path_pattern", "linked_handler_fqn", "linked_method",
                "flow_step", "evidence_source", "confidence", "attributes"
        );
    }

    @Test
    void consistencyScenarioFactsTableExists() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'consistency_scenario_facts'
                """)
                .query(String.class)
                .list();

        assertThat(columns).contains(
                "scenario_id", "pattern", "scope_kind", "scope_ref",
                "primary_store", "primary_physical", "participants",
                "poll_strategy", "evidence_source", "confidence"
        );
    }

    @Test
    void workspaceCatalogConfigTablesExist() {
        List<String> tables = jdbcClient.sql("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name LIKE 'workspace_%'
                ORDER BY table_name
                """)
                .query(String.class)
                .list();

        assertThat(tables).contains(
                "workspace_catalog_library",
                "workspace_service_module",
                "workspace_symbol_classpath",
                "workspace_bundle",
                "workspace_bundle_index_order",
                "workspace_org_settings"
        );
    }

    @Test
    void contractFactsTablesExist() {
        List<String> opColumns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'contract_operation_facts'
                ORDER BY column_name
                """)
                .query(String.class)
                .list();

        assertThat(opColumns).contains(
                "operation_id", "spec_domain", "http_method", "path_template",
                "mapped_service_name", "request_field_summary", "response_field_summary"
        );

        List<String> schemaColumns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'contract_schema_facts'
                """)
                .query(String.class)
                .list();

        assertThat(schemaColumns).contains(
                "schema_id", "schema_title", "top_level_fields", "required_fields", "nested_field_paths"
        );

        List<String> testCallColumns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'test_http_call_facts'
                ORDER BY column_name
                """)
                .query(String.class)
                .list();

        assertThat(testCallColumns).contains(
                "http_method", "path", "path_normalized", "path_constant_ref", "file_path"
        );
    }

    @Test
    void pubsubResourceUniqueIndexIncludesLinkedClass() {
        String indexDef = jdbcClient.sql("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'pubsub_resource_facts' AND indexname = 'uq_pubsub_resource'
                """)
                .query(String.class)
                .optional()
                .orElse("");

        assertThat(indexDef)
                .as("V21 migration must widen uq_pubsub_resource for BL-051 multi-publisher HTTP_PUBSUB facts")
                .contains("linked_class_fqn");
    }

    @Test
    void graphIndexesExist() {
        List<String> indexes = jdbcClient.sql("""
                SELECT indexname FROM pg_indexes
                WHERE tablename IN ('graph_nodes', 'graph_edges')
                """)
                .query(String.class)
                .list();

        assertThat(indexes).contains(
                "idx_edges_from", "idx_edges_to", "idx_nodes_fqn", "idx_nodes_service"
        );
    }

    @Test
    void asyncRetryPathFactsTableExists() {
        List<String> columns = jdbcClient.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'async_retry_path_facts'
                ORDER BY column_name
                """)
                .query(String.class)
                .list();

        assertThat(columns).contains(
                "org_id", "service_id", "env_lane", "module_name", "linked_topic",
                "bq_dataset", "bq_table", "source_ref", "evidence_source"
        );
    }
}
