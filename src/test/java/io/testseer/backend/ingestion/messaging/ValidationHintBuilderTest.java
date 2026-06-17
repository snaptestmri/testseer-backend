package io.testseer.backend.ingestion.messaging;

import io.testseer.backend.ingestion.FactBatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationHintBuilderTest {

    private final ValidationHintBuilder builder =
            new ValidationHintBuilder(MessagingTestFixtures.quotientRulePackLoader());

    @Test
    void build_emitsTopicAndDbPollHints() {
        var pubsub = List.of(new FactBatch.PubSubResourceFact(
                "TOPIC", "PDN_T.PARTNER_ADAPTER_NOTIFICATION", "pdn", "pdn", null, null,
                "SUBSCRIBE", null, "application-pdn.yaml", "hyvee-adapter",
                "com.example.HyveeOfferAdapter", null, "hyvee-adapter-ns",
                "YAML", 1.0, null));

        var dataAccess = List.of(FactBatch.DataAccessFact.touchpoint(
                "com.example.HyveeOfferAdapter", "onMessage", "WRITE", "MARIADB",
                "partner_offer_call_recorder", "partnerOfferCallRepo", "save",
                "[\"offerId\"]", null, "JAVA_AST", 0.85));

        var gates = List.of(new FactBatch.FlowGateFact(
                "pdn", "com.example.HyveeOfferAdapter", "HYVEE_ADAPTER", null,
                "BUSINESS_RULE", "insertedBy", "FREEDOM", "EQ", "SKIP", null,
                "Offer InsertedBy must be FREEDOM", "JAVA_AST", null, 0.95));

        List<FactBatch.ValidationHintFact> hints = builder.build(pubsub, dataAccess, gates, "pdn");

        assertThat(hints).anyMatch(h -> "TOPIC".equals(h.hintKind()) || "SUBSCRIPTION".equals(h.hintKind()));
        assertThat(hints).anyMatch(h -> "DB_POLL".equals(h.hintKind()));
        assertThat(hints).anyMatch(h -> "GATE".equals(h.hintKind()));
    }
}
