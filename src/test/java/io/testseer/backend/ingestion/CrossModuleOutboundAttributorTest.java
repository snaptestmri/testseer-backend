package io.testseer.backend.ingestion;

import io.testseer.backend.ingestion.FactBatch.OutboundCallFact;
import io.testseer.backend.ingestion.ParsedModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CrossModuleOutboundAttributorTest {

    private final CrossModuleOutboundAttributor attributor = new CrossModuleOutboundAttributor();

    @Test
    void attributesProducerOutboundToCallingHandlerClass() {
        String producer = "com.quotient.platform.receipt.common.producer.SubmissionEventProducer";
        String controller = "com.quotient.platform.receiptservice.web.api.ReceiptSubmitServiceApiController";
        String service = "com.quotient.platform.receiptservice.service.ReceiptSubmissionService";

        ParsedModel producerModel = new ParsedModel(
                "SubmissionEventProducer.java", producer,
                List.of("Component"), List.of(), List.of(),
                List.of(), List.of(new ParsedModel.OutboundCallDef(
                        "KafkaTemplate", "POST", "QUOT.RECEIPTS.SUBMISSIONS.EVENTS", "publish")),
                false, null, null, List.of(), List.of(),
                List.of(), List.of(), List.of(), null, List.of());

        ParsedModel serviceModel = new ParsedModel(
                "ReceiptSubmissionService.java", service,
                List.of("Service"), List.of(), List.of("SubmissionEventProducer"),
                List.of(), List.of(), false, null, null, List.of(), List.of(),
                List.of(new ParsedModel.FieldInjectionDef(
                        "submissionEventProducer", "SubmissionEventProducer", null, "Autowired")),
                List.of(new ParsedModel.MethodCallDef(
                        "processSubmission", producer, "publish", "submissionEventProducer")),
                List.of(), null, List.of());

        ParsedModel controllerModel = new ParsedModel(
                "ReceiptSubmitServiceApiController.java", controller,
                List.of("RestController"), List.of(), List.of("ReceiptSubmissionService"),
                List.of(), List.of(), false, null, null, List.of(), List.of(),
                List.of(new ParsedModel.FieldInjectionDef(
                        "receiptSubmissionService", "ReceiptSubmissionService", null, "Autowired")),
                List.of(new ParsedModel.MethodCallDef(
                        "submitReceipt", service, "processSubmission", "receiptSubmissionService")),
                List.of(), null, List.of("ReceiptSubmitServiceApi"));

        OutboundCallFact producerFact = new OutboundCallFact(
                producer, "POST", "QUOT.RECEIPTS.SUBMISSIONS.EVENTS", "javaparser", 0.9);

        List<OutboundCallFact> attributed = attributor.attributeToCallers(
                List.of(producerModel, serviceModel, controllerModel),
                List.of(producerFact));

        assertThat(attributed).extracting(OutboundCallFact::sourceSymbol)
                .contains(service, controller);
    }
}
