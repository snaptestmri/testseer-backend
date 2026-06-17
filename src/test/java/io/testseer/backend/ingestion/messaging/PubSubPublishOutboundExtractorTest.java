package io.testseer.backend.ingestion.messaging;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.ParsedModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PubSubPublishOutboundExtractorTest {

    private final PubSubPublishOutboundExtractor extractor = new PubSubPublishOutboundExtractor();

    @Test
    void extract_linksFreedomUmoTopic() {
        String source = """
                class FreedomOfferUpdateEventPublisher {
                    private PubSubMsgGateway publishGateway;
                    private PubSubPublisherConfig publisherConfig;
                    private String freedomumo;
                    void postConstruct() {
                        freedomumo = publisherConfig.getTopicId().get("freedomumo");
                    }
                    boolean sendUpdateManageOfferEvent() {
                        publishGateway.sendByteArrayToPubSub(freedomumo, event.toByteArray());
                    }
                }
                """;

        ParsedModel model = new ParsedModel(
                "FreedomOfferUpdateEventPublisher.java",
                "com.example.FreedomOfferUpdateEventPublisher",
                List.of(), List.of(), List.of(), List.of(), List.of(),
                false, null, null, List.of(), List.of(),
                List.of(new ParsedModel.FieldInjectionDef(
                        "publishGateway", "PubSubMsgGateway", null, "Autowired")),
                List.of(new ParsedModel.MethodCallDef(
                        "sendUpdateManageOfferEvent",
                        null,
                        "sendByteArrayToPubSub",
                        "publishGateway")),
                List.of(), null, List.of());

        var pubsub = List.of(new FactBatch.PubSubResourceFact(
                "TOPIC", "DEV_T.UMO_EVENT", "dev", "dev", null, null,
                "PUBLISH", "pubsub.publisher.topicId.freedomumo",
                "application-dev.yaml", "partner-adapter-consumer",
                "com.example.FreedomOfferUpdateEventPublisher", "sendUpdateManageOfferEvent",
                null, "RULE_PACK", 0.99, null));

        List<FactBatch.OutboundCallFact> facts = extractor.extract(
                List.of(model),
                pubsub,
                Map.of(model.classFqn(), source));

        assertThat(facts).anyMatch(f ->
                "PUBSUB".equals(f.httpMethod())
                        && "DEV_T.UMO_EVENT".equals(f.path())
                        && f.sourceSymbol().contains("FreedomOfferUpdateEventPublisher"));
    }

    @Test
    void inferSpringLeaf_readsTopicIdGet() {
        String source = "freedomumo = publisherConfig.getTopicId().get(\"freedomumo\");";
        assertThat(PubSubPublishOutboundExtractor.inferSpringLeaf(source)).isEqualTo("freedomumo");
    }
}
