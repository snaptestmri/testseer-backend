package io.testseer.backend.graph;

import io.testseer.backend.AbstractIntegrationTest;
import io.testseer.backend.IntegrationTestDb;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalEndpointGraphProjectorIntegrationTest extends AbstractIntegrationTest {

    @Autowired ExternalEndpointGraphProjector projector;
    @Autowired ServiceRegistryService serviceRegistry;
    @Autowired JdbcClient db;

    String serviceId;

    @BeforeEach
    void setup() {
        IntegrationTestDb.clearCoreFacts(db);
        serviceId = serviceRegistry.register(new RegistrationRequest(
                "quotient", "riq-partner-adapter-suite", "partner-adapter-suite", "MAVEN", "service", null, null, null
        )).serviceId();
    }

    @Test
    void project_upsertsExternalNodeForCallSiteBeforeEdge() {
        FactBatch batch = FactBatch.create(
                "job-1", "quotient", "riq-partner-adapter-suite", serviceId, "abc", "LOCAL",
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(),
                List.of(new FactBatch.ExternalCallSiteFact(
                        "com.example.PartnerAdapter#notify",
                        null, null, null, null, null, null,
                        "dg:webhook_notify",
                        "YAML_CALL_SITE",
                        0.8)));

        projector.project(batch);

        String nodeId = GraphNodeIds.externalEndpointNode("quotient", "unknown", "dg:webhook_notify");
        Integer nodeCount = db.sql("SELECT COUNT(*) FROM graph_nodes WHERE id = :id")
                .param("id", nodeId)
                .query(Integer.class)
                .single();
        Integer edgeCount = db.sql("SELECT COUNT(*) FROM graph_edges WHERE to_node = :id")
                .param("id", nodeId)
                .query(Integer.class)
                .single();

        assertThat(nodeCount).isEqualTo(1);
        assertThat(edgeCount).isEqualTo(1);
    }
}
