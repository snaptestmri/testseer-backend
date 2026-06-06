package io.testseer.backend.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PeripheralDetectorTest {

    private final PeripheralDetector detector = new PeripheralDetector();

    @Test
    void kafkaListener_emitsTier1() {
        ParsedModel model = modelWithAnnotations(List.of("KafkaListener", "Service"));
        List<FactBatch.PeripheralFact> facts = detector.detect(model);

        assertThat(facts).anyMatch(f ->
                f.peripheralType().equals("kafka") &&
                f.detectionTier() == 1 &&
                f.prerequisiteText().contains("Testcontainers")
        );
    }

    @Test
    void redisTemplate_emitsTier1() {
        ParsedModel model = modelWithFieldType("RedisTemplate");
        List<FactBatch.PeripheralFact> facts = detector.detect(model);

        assertThat(facts).anyMatch(f ->
                f.peripheralType().equals("redis") && f.detectionTier() == 1);
    }

    @Test
    void oracleJdbcDriver_emitsTier2() {
        ParsedModel model = modelWithAnnotations(List.of("Service"));
        model = modelWithClassContent(model, "oracle.jdbc.OracleDriver");
        List<FactBatch.PeripheralFact> facts = detector.detect(model);

        assertThat(facts).anyMatch(f ->
                f.peripheralType().equals("oracle") &&
                f.detectionTier() == 2 &&
                f.prerequisiteText().contains("Verify")
        );
    }

    @Test
    void springCloudConfig_emitsTier3() {
        ParsedModel model = modelWithAnnotations(List.of("EnableConfigServer"));
        List<FactBatch.PeripheralFact> facts = detector.detect(model);

        assertThat(facts).anyMatch(f ->
                f.detectionTier() == 3 &&
                f.prerequisiteText().contains(".testseer/config.yml")
        );
    }

    @Test
    void plainSpringController_emitsNoPeripherals() {
        ParsedModel model = modelWithAnnotations(List.of("RestController"));
        assertThat(detector.detect(model)).isEmpty();
    }

    private static ParsedModel modelWithAnnotations(List<String> annotations) {
        return new ParsedModel("Svc.java", "com.example.Svc",
                annotations, List.of(), List.of(), List.of(), List.of(), false, null,
                null, List.of(), List.of());
    }

    private static ParsedModel modelWithFieldType(String type) {
        return new ParsedModel("Svc.java", "com.example.Svc",
                List.of(), List.of(), List.of(type), List.of(), List.of(), false, null,
                null, List.of(), List.of());
    }

    private static ParsedModel modelWithClassContent(ParsedModel base, String keyword) {
        // Simulate oracle detection via constructor params or field types
        return new ParsedModel(base.filePath(), base.classFqn(),
                base.annotations(), List.of(keyword), base.fieldInjectionTypes(),
                base.endpoints(), base.outboundCalls(), false, null,
                null, List.of(), List.of());
    }
}
