package io.testseer.backend.analysis;

import io.testseer.backend.graph.GraphFactProjector;
import io.testseer.backend.graph.GraphNodeRepository;
import io.testseer.backend.ingestion.DualWriteService;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.FactExtractor;
import io.testseer.backend.ingestion.ParsedModel;
import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration," +
        "com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubAutoConfiguration"
})
@Testcontainers
class ImpactAnalysisIntegrationTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @Autowired ImpactAnalysisService impactService;
    @Autowired DualWriteService dualWriteService;
    @Autowired GraphFactProjector graphProjector;
    @Autowired FactExtractor factExtractor;
    @Autowired ServiceRegistryService svcRegistry;
    @Autowired GraphNodeRepository nodeRepo;
    @Autowired JdbcClient db;

    String serviceId;
    String commitSha = "deadbeef";

    @BeforeEach
    void setup() {
        db.sql("DELETE FROM graph_edges").update();
        db.sql("DELETE FROM graph_nodes").update();
        db.sql("DELETE FROM outbound_call_facts").update();
        db.sql("DELETE FROM symbol_facts").update();
        db.sql("DELETE FROM analysis_runs").update();
        db.sql("DELETE FROM service_registry").update();

        var reg = svcRegistry.register(new RegistrationRequest(
                "acme", "orders", "orders", "MAVEN", "service",
                List.of("src/main/java"), List.of("src/test/java"), null));
        serviceId = reg.serviceId();

        ParsedModel controller = new ParsedModel(
                "src/main/java/io/orders/OrderController.java",
                "io.orders.OrderController",
                List.of("RestController"),
                List.of(), List.of(),
                List.of(new ParsedModel.EndpointDef("GET", "/orders/{id}", "getOrder")),
                List.of(), false, null,
                null, List.of(), List.of());

        ParsedModel controllerTest = new ParsedModel(
                "src/test/java/io/orders/OrderControllerTest.java",
                "io.orders.OrderControllerTest",
                List.of(), List.of(), List.of(),
                List.of(), List.of(), false, null,
                null, List.of(), List.of());

        FactBatch batch = new FactBatch(
                "job-1", "acme", "orders", serviceId, commitSha, "DELTA",
                java.util.stream.Stream.concat(
                        factExtractor.extractSymbolFacts(controller).stream(),
                        factExtractor.extractSymbolFacts(controllerTest).stream()
                ).toList(),
                List.of(), List.of(), List.of());

        dualWriteService.write(batch, List.of(controller, controllerTest));
        graphProjector.project(batch, List.of(controller, controllerTest));

        db.sql("""
                INSERT INTO analysis_runs
                  (job_id, org_id, service_id, commit_sha, job_type, status, attempt, enqueued_at, completed_at)
                VALUES (:jobId, 'acme', :svcId, :sha, 'PR', 'COMPLETE', 1, :now, :now)
                """)
                .param("jobId", "job-1")
                .param("svcId", serviceId)
                .param("sha", commitSha)
                .param("now", Instant.now())
                .update();
    }

    @Test
    void buildReport_returnsChangedSymbolsAndUnitTestSuggestion() {
        ImpactReport report = impactService.buildReport(serviceId, commitSha);

        assertThat(report.changedSymbols())
                .extracting(ImpactReport.ChangedSymbol::symbolFqn)
                .contains("io.orders.OrderController");

        assertThat(report.suggestedTestScope())
                .anyMatch(s -> "UNIT".equals(s.type())
                        && s.exists()
                        && "io.orders.OrderControllerTest".equals(s.className()));
    }

    @Test
    void buildReport_detectsOutboundCallConsumers() {
        var billing = svcRegistry.register(new RegistrationRequest(
                "acme", "billing", "billing", "MAVEN", "service",
                List.of("src/main/java"), List.of("src/test/java"), null));

        ParsedModel billingClient = new ParsedModel(
                "src/main/java/io/billing/BillingClient.java",
                "io.billing.BillingClient",
                List.of(), List.of(), List.of(),
                List.of(),
                List.of(new ParsedModel.OutboundCallDef(
                        "RestClient", "GET", "/orders/{id}", "getOrder")),
                false, null,
                null, List.of(), List.of());

        FactBatch billingBatch = new FactBatch(
                "job-2", "acme", "billing", billing.serviceId(), commitSha, "BASELINE",
                factExtractor.extractSymbolFacts(billingClient),
                factExtractor.extractOutboundCallFacts(billingClient),
                List.of(), List.of());

        dualWriteService.write(billingBatch, List.of(billingClient));
        graphProjector.project(billingBatch, List.of(billingClient));

        ImpactReport report = impactService.buildReport(serviceId, commitSha);

        assertThat(report.affectedConsumers())
                .anyMatch(c -> "OUTBOUND_CALL".equals(c.source())
                        && "billing".equals(c.consumerServiceName()));
    }
}
