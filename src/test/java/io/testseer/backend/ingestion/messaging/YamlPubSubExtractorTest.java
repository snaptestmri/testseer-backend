package io.testseer.backend.ingestion.messaging;

import io.testseer.backend.ingestion.FactBatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class YamlPubSubExtractorTest {

    private final YamlPubSubExtractor extractor = new YamlPubSubExtractor();

    @Test
    void extract_findsTopicAndSubscriptionFromApplicationPdnYaml() {
        String yaml = """
                metrics:
                  projectId: prj-promoplat-np
                pubsub:
                  publisher:
                    topicId:
                      offerUpdate: PDN_T.OFFER_UPDATE
                      offer-ingestion: PDN_T.RIQ_OFFER_EVENT
                consumer:
                  riq-offer-event:
                    subscription-id: PDN_S.RIQ_OFFER_EVENT
                """;

        List<YamlPubSubExtractor.ConfigFile> files = List.of(
                new YamlPubSubExtractor.ConfigFile(
                        "offer-events-consumer/src/main/resources/application-pdn.yaml", yaml)
        );

        List<FactBatch.PubSubResourceFact> facts = extractor.extract(files);

        assertThat(facts).anyMatch(f ->
                "TOPIC".equals(f.resourceKind())
                        && "PDN_T.OFFER_UPDATE".equals(f.shortId())
                        && "PUBLISH".equals(f.role())
                        && "pdn".equals(f.envLane()));
        assertThat(facts).anyMatch(f ->
                "SUBSCRIPTION".equals(f.resourceKind())
                        && "PDN_S.RIQ_OFFER_EVENT".equals(f.shortId())
                        && "SUBSCRIBE".equals(f.role()));
        assertThat(facts).anyMatch(f ->
                "prj-promoplat-np".equals(f.gcpProject())
                        && f.fullResourceId() != null
                        && f.fullResourceId().contains("prj-promoplat-np"));
    }
}
