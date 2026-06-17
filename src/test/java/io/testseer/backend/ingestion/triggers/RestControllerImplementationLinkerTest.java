package io.testseer.backend.ingestion.triggers;

import io.testseer.backend.config.TriggerRulePack;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.ParsedModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RestControllerImplementationLinkerTest {

    private final RestControllerImplementationLinker linker = new RestControllerImplementationLinker();

    private static final String IFACE =
            "com.quotient.platform.receiptservice.web.api.ReceiptSubmitServiceApi";
    private static final String IMPL =
            "com.quotient.platform.receiptservice.web.api.ReceiptSubmitServiceApiController";

    @Test
    void linksRestTriggerFromInterfaceToRestController() {
        ParsedModel api = ParsedModel.of(
                "ReceiptSubmitServiceApi.java", IFACE,
                List.of(), List.of(), List.of(),
                List.of(new ParsedModel.EndpointDef("POST", "/receipt/submit", "submitReceipt")),
                List.of(), false, null, null, List.of(), List.of());

        ParsedModel controller = new ParsedModel(
                "ReceiptSubmitServiceApiController.java", IMPL,
                List.of("RestController"), List.of(), List.of(),
                List.of(), List.of(), false, null, null, List.of(), List.of(),
                List.of(), List.of(), List.of(), null,
                List.of(IFACE));

        FactBatch.EntryTriggerFact trigger = new FactBatch.EntryTriggerFact(
                "unknown:post:/receipt/submit:receiptsubmitserviceapi",
                "REST_INBOUND", "INBOUND", "dev", "unknown", "EXTERNAL",
                "POST", "/receipt/submit", IFACE, "submitReceipt",
                null, IFACE + ".java", "JAVA_HEURISTIC", 0.8,
                "{\"classFqn\":\"" + IFACE + "\"}");

        List<FactBatch.EntryTriggerFact> linked = linker.link(
                List.of(trigger), List.of(api, controller));

        assertThat(linked).singleElement().satisfies(f -> {
            assertThat(f.linkedHandlerFqn()).isEqualTo(IMPL);
            assertThat(f.evidenceSource()).isEqualTo("REST_IMPL_LINKER");
            assertThat(f.attributes()).contains("handlerInterfaceFqn");
            assertThat(f.attributes()).contains("handlerImplFqn");
        });
    }

    @Test
    void leavesTriggerUnchangedWhenNoImplementation() {
        FactBatch.EntryTriggerFact trigger = new FactBatch.EntryTriggerFact(
                "unknown:post:/receipt/submit:orphanapi",
                "REST_INBOUND", "INBOUND", "dev", "unknown", "EXTERNAL",
                "POST", "/receipt/submit", "com.example.OrphanApi", "submit",
                null, "OrphanApi.java", "JAVA_HEURISTIC", 0.8, "{}");

        List<FactBatch.EntryTriggerFact> linked = linker.link(List.of(trigger), List.of());

        assertThat(linked).singleElement().extracting(FactBatch.EntryTriggerFact::linkedHandlerFqn)
                .isEqualTo("com.example.OrphanApi");
    }
}
