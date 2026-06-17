package io.testseer.backend.admin;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration," +
        "com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubAutoConfiguration,com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubReactiveAutoConfiguration"
})
@Testcontainers
@Import(io.testseer.backend.KafkaTestConfiguration.class)
class IndexClearIntegrationTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    @Container @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @Autowired IndexClearService clearService;
    @Autowired ServiceRegistryService registryService;
    @Autowired io.testseer.backend.ingestion.DualWriteService dualWriteService;
    @Autowired JdbcClient db;

    String serviceId;

    @BeforeEach
    void setup() {
        db.sql("DELETE FROM async_retry_path_facts").update();
        db.sql("DELETE FROM pubsub_resource_facts").update();
        db.sql("DELETE FROM symbol_facts").update();
        db.sql("DELETE FROM service_registry").update();

        serviceId = registryService.register(new RegistrationRequest(
                "quotient", "test-repo", "test-repo", "MAVEN", "service",
                List.of("src/main/java"), List.of("src/test/java"), null
        )).serviceId();
    }

    @Test
    void clearMessaging_removesOptionCFactsOnly() {
        FactBatch batch = FactBatch.create(
                "job-c-1", "quotient", "test-repo", serviceId, "abc", "DELTA",
                List.of(new FactBatch.SymbolFact("A.java", "com.A", "CLASS", "{}", "jp", 1.0)),
                List.of(), List.of(), List.of(),
                List.of(new FactBatch.PubSubResourceFact(
                        "TOPIC", "PDN_T.TEST", "pdn", "pdn", null, null,
                        "PUBLISH", null, "application-pdn.yaml", "mod", null, null, null,
                        "YAML", 1.0, null)),
                List.of(), List.of(), List.of(), List.of());
        dualWriteService.write(batch, List.of());

        IndexClearResponse resp = clearService.clearMessaging(serviceId);

        assertThat(resp.scope()).isEqualTo("MESSAGING");
        long symbols = count("symbol_facts");
        long pubsub = count("pubsub_resource_facts");
        assertThat(symbols).isEqualTo(1);
        assertThat(pubsub).isZero();
    }

    @Test
    void clearService_removesAllFacts() {
        FactBatch batch = FactBatch.core(
                "job-c-2", "quotient", "test-repo", serviceId, "abc", "DELTA",
                List.of(new FactBatch.SymbolFact("B.java", "com.B", "CLASS", "{}", "jp", 1.0)),
                List.of(), List.of(), List.of());
        dualWriteService.write(batch, List.of());

        clearService.clearService(serviceId);

        assertThat(count("symbol_facts")).isZero();
    }

    @Test
    void clearOrg_withRegistry_removesAsyncRetryPathFactsBeforeRegistry() {
        FactBatch batch = FactBatch.create(
                "job-c-3", "quotient", "test-repo", serviceId, "abc", "DELTA",
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of())
                .withAsyncRetryPaths(List.of(new FactBatch.AsyncRetryPathFact(
                        "pdn", "retry-job", "PDN_T.TEST",
                        "PDN_DLQ_RETRY", "ACTIVATE_OFFER_DLQ",
                        "retry-job/application-pdn.yaml", "YAML_DLQ_RETRY", 0.9, null)));
        dualWriteService.write(batch, List.of());

        IndexClearResponse resp = clearService.clearOrg("quotient", true);

        assertThat(resp.scope()).isEqualTo("ORG");
        assertThat(resp.deletedCounts()).containsEntry("asyncRetryPathFacts", 1);
        assertThat(resp.deletedCounts()).containsEntry("serviceRegistry", 1);
        assertThat(count("async_retry_path_facts")).isZero();
        assertThat(registryCount()).isZero();
    }

    private long registryCount() {
        return db.sql("SELECT COUNT(*) FROM service_registry WHERE org_id = :orgId")
                .param("orgId", "quotient")
                .query(Long.class)
                .single();
    }

    private long count(String table) {
        return db.sql("SELECT COUNT(*) FROM " + table + " WHERE service_id = :id")
                .param("id", serviceId)
                .query(Long.class)
                .single();
    }
}
