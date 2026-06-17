package io.testseer.backend;

import org.springframework.jdbc.core.simple.JdbcClient;

public final class IntegrationTestDb {

    private IntegrationTestDb() {}

    public static void clearMessagingAndGraph(JdbcClient db) {
        db.sql("DELETE FROM validation_hint_facts").update();
        db.sql("DELETE FROM external_endpoint_facts").update();
        db.sql("DELETE FROM external_call_site_facts").update();
        db.sql("DELETE FROM entry_trigger_facts").update();
        db.sql("DELETE FROM async_retry_path_facts").update();
        db.sql("DELETE FROM maven_module_facts").update();
        db.sql("DELETE FROM maven_dependency_facts").update();
        db.sql("DELETE FROM flow_gate_facts").update();
        db.sql("DELETE FROM data_access_facts").update();
        db.sql("DELETE FROM data_object_facts").update();
        db.sql("DELETE FROM accessor_method_facts").update();
        db.sql("DELETE FROM schema_object_facts").update();
        db.sql("DELETE FROM consistency_scenario_facts").update();
        db.sql("DELETE FROM contract_operation_facts").update();
        db.sql("DELETE FROM contract_schema_facts").update();
        db.sql("DELETE FROM test_http_call_facts").update();
        db.sql("DELETE FROM message_schema_facts").update();
        db.sql("DELETE FROM pubsub_resource_facts").update();
        db.sql("DELETE FROM graph_edges").update();
        db.sql("DELETE FROM graph_nodes").update();
    }

    public static void clearCoreFacts(JdbcClient db) {
        clearMessagingAndGraph(db);
        db.sql("DELETE FROM analysis_runs").update();
        db.sql("DELETE FROM outbound_call_facts").update();
        db.sql("DELETE FROM symbol_facts").update();
        db.sql("DELETE FROM peripheral_facts").update();
        db.sql("DELETE FROM unsupported_construct_facts").update();
        db.sql("DELETE FROM service_registry").update();
    }
}
