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
}
