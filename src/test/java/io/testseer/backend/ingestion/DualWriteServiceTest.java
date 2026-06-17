package io.testseer.backend.ingestion;

import io.testseer.backend.AbstractIntegrationTest;
import io.testseer.backend.IntegrationTestDb;
import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceRegistryService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DualWriteServiceTest extends AbstractIntegrationTest {
    @Autowired DualWriteService dualWriteService;
    @Autowired ServiceRegistryService registryService;
    @Autowired JdbcClient jdbcClient;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired AnalysisRunTracker runTracker;

    String serviceId;

    @BeforeEach
    void setup() {
        IntegrationTestDb.clearCoreFacts(jdbcClient);
        mongoTemplate.dropCollection("parsed_models");

        serviceId = registryService.register(new RegistrationRequest(
                "acme", "order-service", "orders", "MAVEN",
                "service", List.of("src/main/java"), null, null
        )).serviceId();
    }

    @Test
    void write_persists_symbolFacts_to_postgres() {
        FactBatch batch = FactBatch.core(
                "job-001", "acme", "order-service", serviceId, "abc123", "DELTA",
                List.of(new FactBatch.SymbolFact(
                        "OrderController.java", "com.example.OrderController",
                        "CLASS", "{}", "javaparser", 1.0
                )),
                List.of(), List.of(), List.of()
        );

        dualWriteService.write(batch, List.of());

        long count = jdbcClient
                .sql("SELECT COUNT(*) FROM symbol_facts WHERE service_id = :id")
                .param("id", serviceId)
                .query(Long.class)
                .single();

        assertThat(count).isEqualTo(1);
    }

    @Test
    void write_persists_parsedModel_to_mongodb() {
        ParsedModel model = ParsedModel.of(
                "OrderController.java", "com.example.OrderController",
                List.of("RestController"), List.of(), List.of(),
                List.of(), List.of(), false, null,
                null, List.of(), List.of()
        );
        FactBatch batch = FactBatch.core(
                "job-002", "acme", "order-service", serviceId, "abc123", "DELTA",
                List.of(), List.of(), List.of(), List.of()
        );

        dualWriteService.write(batch, List.of(model));

        long count = mongoTemplate.getCollection("parsed_models")
                .countDocuments(new Document("serviceId", serviceId));
        assertThat(count).isEqualTo(1);
    }

    @Test
    void markComplete_sets_COMPLETE_status_in_analysis_runs() {
        jdbcClient.sql("""
            INSERT INTO analysis_runs(job_id, org_id, service_id, commit_sha, job_type, status, attempt, enqueued_at)
            VALUES ('j-track-001', 'acme', :svcId, 'abc', 'PR', 'QUEUED', 1, now())
            """).param("svcId", serviceId).update();

        runTracker.markRunning("j-track-001");
        runTracker.markComplete("j-track-001");

        String status = jdbcClient.sql(
                "SELECT status FROM analysis_runs WHERE job_id = 'j-track-001'")
                .query(String.class).single();

        assertThat(status).isEqualTo("COMPLETE");
    }

    @Test
    void write_persists_v8MessagingFacts_to_postgres() {
        FactBatch batch = FactBatch.create(
                "job-v8", "acme", "order-service", serviceId, "abc123", "DELTA",
                List.of(), List.of(), List.of(), List.of(),
                List.of(new FactBatch.PubSubResourceFact(
                        "TOPIC", "PDN_T.TEST", "pdn", "pdn", null, null,
                        "PUBLISH", "testTopic", "application-pdn.yaml", "orders",
                        null, null, null, "YAML", 1.0, null)),
                List.of(new FactBatch.MessageSchemaFact(
                        "QMsgEvent", "TestEvent", "[]", null,
                        "com.example.Handler", null, "INBOUND",
                        "PDN_T.TEST", null, "Test.proto", "PROTO", 1.0)),
                List.of(FactBatch.DataAccessFact.touchpoint(
                        "com.example.Handler", "onMessage", "READ", "MARIADB",
                        "offer", "offerRepo", "findById", "[\"offerId\"]", null,
                        "JAVA_AST", 0.85)),
                List.of(new FactBatch.FlowGateFact(
                        "pdn", "com.example.Handler", "TEST", null,
                        "CODE_FLAG", "enabled", "true", "EQ", "SKIP", null,
                        "Enable handler", "YAML", null, 0.9)),
                List.of(new FactBatch.ValidationHintFact(
                        "TEST", "TOPIC", "PDN_T.TEST", "com.example.Handler", "pdn")));

        dualWriteService.write(batch, List.of());

        assertThat(countTable("pubsub_resource_facts")).isEqualTo(1);
        assertThat(countTable("message_schema_facts")).isEqualTo(1);
        assertThat(countTable("data_access_facts")).isEqualTo(1);
        assertThat(countTable("flow_gate_facts")).isEqualTo(1);
        assertThat(countTable("validation_hint_facts")).isEqualTo(1);
    }

    @Test
    void write_persists_multipleHttpPubSubPublishersForSameTopic() {
        FactBatch batch = FactBatch.create(
                "job-v8b", "acme", "order-service", serviceId, "abc123", "DELTA",
                List.of(), List.of(), List.of(), List.of(),
                List.of(
                        new FactBatch.PubSubResourceFact(
                                "TOPIC", "DEV_T.NOTIFICATION_REQ", "unknown", "dev", null,
                                "http://example/pubsub/service/publish",
                                "PUBLISH", "rest.apis.pubsub.topic-name",
                                "evaluation-consumers/transaction-eval-consumer/kubernetes-manifests/dev/transaction-eval-consumer.dev.config-map.yaml#application.yaml",
                                "transaction-eval-consumer",
                                "com.example.ReceiptTxnEvalProcessor", "notify", null,
                                "HTTP_PUBSUB_LINKER", 0.94, "{\"transport\":\"HTTP_PUBSUB\"}"),
                        new FactBatch.PubSubResourceFact(
                                "TOPIC", "DEV_T.NOTIFICATION_REQ", "unknown", "dev", null,
                                "http://example/pubsub/service/publish",
                                "PUBLISH", "rest.apis.pubsub.topic-name",
                                "evaluation-consumers/transaction-eval-consumer/kubernetes-manifests/dev/transaction-eval-consumer.dev.config-map.yaml#application.yaml",
                                "transaction-eval-consumer",
                                "com.example.CorrectedTxnEvalProcessor", "notify", null,
                                "HTTP_PUBSUB_LINKER", 0.94, "{\"transport\":\"HTTP_PUBSUB\"}")),
                List.of(), List.of(), List.of(), List.of());

        dualWriteService.write(batch, List.of());

        assertThat(countTable("pubsub_resource_facts")).isEqualTo(2);
    }

    @Test
    void write_dedupesExactDuplicatePubSubResourceFacts() {
        FactBatch.PubSubResourceFact fact = new FactBatch.PubSubResourceFact(
                "TOPIC", "PDN_T.TEST", "pdn", "pdn", null, null,
                "PUBLISH", "testTopic", "application-pdn.yaml", "orders",
                null, null, null, "YAML", 1.0, null);
        FactBatch batch = FactBatch.create(
                "job-v8c", "acme", "order-service", serviceId, "abc123", "DELTA",
                List.of(), List.of(), List.of(), List.of(),
                List.of(fact, fact),
                List.of(), List.of(), List.of(), List.of());

        dualWriteService.write(batch, List.of());

        assertThat(countTable("pubsub_resource_facts")).isEqualTo(1);
    }

    @Test
    void write_persists_v9ExternalEndpointFacts_to_postgres() {
        FactBatch batch = FactBatch.create(
                "job-v9", "acme", "order-service", serviceId, "abc123", "DELTA",
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new FactBatch.ExternalEndpointFact(
                        "hyvee:offer_sync", "hyvee", "OFFER_SYNC", "POST",
                        "https://example.com/{id}", "https://example.com/promo",
                        "pdn", "EXTERNAL", "integrator.partners.hyvee.offer-endpoint",
                        "application-pdn.yaml", "com.example.HyveeOfferAdapter",
                        "com.example.HyveeRestClient", "HYVEE_ADAPTER", "LmsSession",
                        "YAML+javaparser", 0.92, "{}")),
                List.of(new FactBatch.ExternalCallSiteFact(
                        "com.example.HyveeRestClient#sendOfferSyncRequest",
                        "hyveeConfigs.getOfferEndpoint()", "integrator.partners.hyvee",
                        "offerEndpoint", "RestTemplate", "exchange", "POST",
                        "hyvee:offer_sync", "javaparser+yaml", 0.92)));

        dualWriteService.write(batch, List.of());

        assertThat(countTable("external_endpoint_facts")).isEqualTo(1);
        assertThat(countTable("external_call_site_facts")).isEqualTo(1);
    }

    @Test
    void write_replacesExternalEndpointFactsOnReindex() {
        FactBatch initial = FactBatch.create(
                "job-v9a", "acme", "order-service", serviceId, "abc123", "DELTA",
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new FactBatch.ExternalEndpointFact(
                        "hyvee:offer_sync", "hyvee", "OFFER_SYNC", "POST",
                        "https://example.com/{id}", null,
                        "dev", "EXTERNAL", "integrator.partners.hyvee.offer-endpoint",
                        "application-dev.yaml", "com.example.HyveeOfferAdapter",
                        "com.example.HyveeRestClient", "HYVEE_ADAPTER", "LmsSession",
                        "YAML+javaparser", 0.92, "{}")),
                List.of());

        dualWriteService.write(initial, List.of());
        assertThat(countTable("external_endpoint_facts")).isEqualTo(1);

        FactBatch updated = FactBatch.create(
                "job-v9b", "acme", "order-service", serviceId, "abc123", "DELTA",
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new FactBatch.ExternalEndpointFact(
                        "hyvee:offer_sync", "hyvee", "OFFER_SYNC", "POST",
                        "https://example.com/{id}", "https://example.com/promo",
                        "dev", "EXTERNAL", "integrator.partners.hyvee.offer-endpoint",
                        "application-dev.yaml", "com.example.HyveeOfferAdapter",
                        "com.example.HyveeRestClient", "HYVEE_ADAPTER", "LmsSession",
                        "YAML+javaparser", 0.92, "{}")),
                List.of());

        dualWriteService.write(updated, List.of());

        assertThat(countTable("external_endpoint_facts")).isEqualTo(1);
        String url = jdbcClient.sql("""
                SELECT url_resolved FROM external_endpoint_facts
                WHERE service_id = :svcId
                """)
                .param("svcId", serviceId)
                .query(String.class)
                .single();
        assertThat(url).isEqualTo("https://example.com/promo");
    }

    @Test
    void write_persists_dataObjectFacts_to_postgres() {
        FactBatch batch = FactBatch.create(
                "job-v10", "acme", "platform-data", serviceId, "abc123", "DELTA",
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(),
                List.of(new FactBatch.DataObjectFact(
                        "com.quotient.platform.data.rdb.dataaccess.offer.entities.offer.PartnerOfferCallRecorderEntity",
                        "com.quotient.platform.domain.offer.PartnerOfferCallRecorder",
                        "MARIADB", "PartnerOfferCallRecorder", "coupons_nextgen", "TABLE",
                        "ENTITY_ANNOTATION", 0.95, null)),
                List.of(), List.of(), List.of());

        dualWriteService.write(batch, List.of());

        assertThat(countTable("data_object_facts")).isEqualTo(1);
    }

    @Test
    void write_persists_entryTriggerFacts_to_postgres() {
        FactBatch batch = FactBatch.create(
                "job-v11", "acme", "order-service", serviceId, "abc123", "DELTA",
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(),
                List.of(), List.of(), List.of(),
                List.of(new FactBatch.EntryTriggerFact(
                        "freedom:post:/update", "WEBHOOK_INBOUND", "INBOUND", "unknown",
                        "freedom", "EXTERNAL", "POST", "/update",
                        "com.example.FreedomWebhookController", "handleFreedomWebhook",
                        "PAYOUT_STATUS", "FreedomWebhookController.java",
                        "RULE_PACK", 0.92, "{}")));

        dualWriteService.write(batch, List.of());

        assertThat(countTable("entry_trigger_facts")).isEqualTo(1);
    }

    @Test
    void write_persists_accessorMethodFacts_to_postgres() {
        FactBatch batch = FactBatch.create(
                "job-v10b", "acme", "platform-data", serviceId, "abc123", "DELTA",
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(),
                List.of(), List.of(new FactBatch.AccessorMethodFact(
                        "DAO",
                        "com.quotient.platform.data.rdb.dataaccess.offer.dao.PartnerOfferCallRecorderDao",
                        "saveToDb", "WRITE",
                        "com.quotient.platform.data.rdb.dataaccess.offer.entities.offer.PartnerOfferCallRecorderEntity",
                        "com.quotient.platform.domain.offer.PartnerOfferCallRecorder",
                        "MARIADB", "PartnerOfferCallRecorder",
                        "DAO_METHOD+ENTITY", 0.93)),
                List.of(), List.of());

        dualWriteService.write(batch, List.of());

        assertThat(countTable("accessor_method_facts")).isEqualTo(1);
    }

    private long countTable(String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table + " WHERE service_id = :id")
                .param("id", serviceId)
                .query(Long.class)
                .single();
    }
}
