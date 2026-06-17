package io.testseer.backend.ingestion.triggers;

import io.testseer.backend.config.TriggerRulePack;
import io.testseer.backend.config.TriggerRulePackLoader;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.ParsedModel;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InboundRestTriggerExtractorTest {

    private final InboundRestTriggerExtractor extractor = new InboundRestTriggerExtractor();

    private final TriggerRulePack rulePack = new TriggerRulePackLoader(
            new FileSystemResource("../config/rule-packs/quotient-triggers.yml"))
            .getRulePack();

    @Test
    void extract_freedomWebhookFromRulePack() {
        ParsedModel model = ParsedModel.of(
                "FreedomWebhookController.java",
                "com.quotient.platform.payment.async.controller.FreedomWebhookController",
                List.of("RestController"),
                List.of(),
                List.of(),
                List.of(new ParsedModel.EndpointDef("POST", "/update", "handleFreedomWebhook")),
                List.of(),
                false,
                null,
                null,
                List.of(),
                List.of()
        );

        List<FactBatch.EntryTriggerFact> facts = extractor.extract(List.of(model), rulePack, "unknown");

        assertThat(facts).hasSize(1);
        FactBatch.EntryTriggerFact fact = facts.get(0);
        assertThat(fact.triggerKind()).isEqualTo("WEBHOOK_INBOUND");
        assertThat(fact.actor()).isEqualTo("freedom");
        assertThat(fact.boundary()).isEqualTo("EXTERNAL");
        assertThat(fact.flowStep()).isEqualTo("PAYOUT_STATUS");
        assertThat(fact.pathPattern()).isEqualTo("/update");
        assertThat(fact.evidenceSource()).isEqualTo("RULE_PACK");
    }

    @Test
    void extract_partnerAdapterIngressByPathPrefix() {
        ParsedModel model = ParsedModel.of(
                "PartnerAdapterController.java",
                "com.quotient.platform.partneradapter.api.PartnerAdapterController",
                List.of("RestController"),
                List.of(),
                List.of(),
                List.of(new ParsedModel.EndpointDef(
                        "POST", "/partner/adapter/offer/{adapter}", "handleOfferUpdate")),
                List.of(),
                false,
                null,
                null,
                List.of(),
                List.of()
        );

        List<FactBatch.EntryTriggerFact> facts = extractor.extract(List.of(model), rulePack, "unknown");

        assertThat(facts).singleElement().satisfies(f -> {
            assertThat(f.triggerKind()).isEqualTo("REST_INBOUND");
            assertThat(f.actor()).isEqualTo("internal");
            assertThat(f.boundary()).isEqualTo("INTERNAL");
            assertThat(f.flowStep()).isEqualTo("PARTNER_ADAPTER_INGRESS");
        });
    }

    @Test
    void extract_heuristicWebhookWhenNoRule() {
        ParsedModel model = ParsedModel.of(
                "NumeratorWebhookApiController.java",
                "com.quotient.platform.numeratorwebhook.web.api.NumeratorWebhookApiController",
                List.of("RestController"),
                List.of(),
                List.of(),
                List.of(new ParsedModel.EndpointDef("POST", "/events", "receive")),
                List.of(),
                false,
                null,
                null,
                List.of(),
                List.of()
        );

        List<FactBatch.EntryTriggerFact> facts =
                extractor.extract(List.of(model), TriggerRulePack.empty(), "unknown");

        assertThat(facts).singleElement().satisfies(f -> {
            assertThat(f.triggerKind()).isEqualTo("WEBHOOK_INBOUND");
            assertThat(f.actor()).isEqualTo("unknown");
            assertThat(f.evidenceSource()).isEqualTo("JAVA_HEURISTIC");
        });
    }

    @Test
    void skipsFeignClientEndpoints() {
        ParsedModel model = ParsedModel.of(
                "OfferIngestionSvcClient.java",
                "com.example.OfferIngestionSvcClient",
                List.of("FeignClient"),
                List.of(),
                List.of(),
                List.of(new ParsedModel.EndpointDef("PUT", "/offer/{offerId}/details", "update")),
                List.of(),
                false,
                null,
                null,
                List.of(),
                List.of()
        );

        assertThat(extractor.extract(List.of(model), rulePack, "unknown")).isEmpty();
    }
}
