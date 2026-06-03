package io.testseer.backend.ingestion;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Component
public class PeripheralDetector {

    public List<FactBatch.PeripheralFact> detect(ParsedModel model) {
        List<FactBatch.PeripheralFact> result = new ArrayList<>();
        List<String> all = allSignals(model);

        // Tier 1 — high confidence, direct Testcontainers recommendation
        checkTier1(result, all, "KafkaListener", "kafka",
                "Use Testcontainers Kafka (org.testcontainers:kafka) for this service");
        checkTier1(result, all, "RabbitListener", "rabbitmq",
                "Use Testcontainers RabbitMQ for this service");
        checkTier1ByType(result, all, "RedisTemplate", "redis",
                "Use Testcontainers Redis for this service");
        checkTier1ByType(result, all, "MongoTemplate", "mongodb",
                "Use Testcontainers MongoDB for this service");
        checkTier1(result, all, "AmazonS3", "s3",
                "Use LocalStack (Testcontainers) for this service");
        if (all.contains("Entity") && all.stream().anyMatch(s -> s.contains("postgresql"))) {
            result.add(tier1("postgres", List.of("@Entity", "postgresql dialect"),
                    "Use Testcontainers PostgreSQL for this service"));
        }

        // Tier 2 — possible on-prem, verify before using Testcontainers
        if (all.stream().anyMatch(s -> s.toLowerCase().contains("oracle"))) {
            result.add(tier2("oracle", List.of("oracle.jdbc"),
                    "Verify before using Testcontainers — Oracle may be on-prem at your org",
                    "oracle.jdbc.OracleDriver detected"));
        }
        if (all.stream().anyMatch(s -> s.toLowerCase().contains("sqlserver"))) {
            result.add(tier2("sqlserver", List.of("sqlserver"),
                    "Verify before using Testcontainers — SQL Server may be on-prem",
                    "SQL Server driver detected"));
        }

        // Tier 3 — manual setup required
        if (all.stream().anyMatch(s -> s.contains("EnableConfigServer") || s.contains("ConfigServer"))) {
            result.add(tier3("spring-cloud-config",
                    List.of("@EnableConfigServer"),
                    "Manual setup required — declare peripheral in .testseer/config.yml",
                    "SPRING_CLOUD_CONFIG"));
        }

        return result;
    }

    private static void checkTier1(List<FactBatch.PeripheralFact> result,
                                   List<String> signals, String signal,
                                   String type, String text) {
        if (signals.contains(signal)) {
            result.add(tier1(type, List.of(signal), text));
        }
    }

    private static void checkTier1ByType(List<FactBatch.PeripheralFact> result,
                                          List<String> signals, String typeKeyword,
                                          String type, String text) {
        if (signals.stream().anyMatch(s -> s.contains(typeKeyword))) {
            result.add(tier1(type, List.of(typeKeyword), text));
        }
    }

    private static FactBatch.PeripheralFact tier1(String type, List<String> signals, String text) {
        return new FactBatch.PeripheralFact(type, 1,
                signals.toString(), text, null);
    }

    private static FactBatch.PeripheralFact tier2(String type, List<String> signals,
                                                    String text, String reasonCode) {
        return new FactBatch.PeripheralFact(type, 2, signals.toString(), text, reasonCode);
    }

    private static FactBatch.PeripheralFact tier3(String type, List<String> signals,
                                                    String text, String reasonCode) {
        return new FactBatch.PeripheralFact(type, 3, signals.toString(), text, reasonCode);
    }

    private static List<String> allSignals(ParsedModel model) {
        return Stream.of(
                model.annotations(),
                model.constructorParamTypes(),
                model.fieldInjectionTypes()
        ).flatMap(List::stream).toList();
    }
}
