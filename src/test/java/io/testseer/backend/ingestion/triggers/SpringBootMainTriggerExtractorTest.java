package io.testseer.backend.ingestion.triggers;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.ParsedModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpringBootMainTriggerExtractorTest {

    private final SpringBootMainTriggerExtractor extractor = new SpringBootMainTriggerExtractor();

    @Test
    void extractsLongRunningConsumerMain() {
        String source = """
                package com.quotient.platform.transaction.eval;
                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                import org.springframework.context.annotation.ComponentScan;
                @SpringBootApplication
                @ComponentScan(basePackageClasses = { ApiConfig.class })
                public class TransactionEvaluationKCApplication {
                    public static void main(String[] args) {
                        SpringApplication.run(TransactionEvaluationKCApplication.class, args);
                    }
                }
                """;

        ParsedModel main = ParsedModel.of(
                "evaluation-consumers/transaction-eval-consumer/src/main/java/com/quotient/platform/transaction/eval/TransactionEvaluationKCApplication.java",
                "com.quotient.platform.transaction.eval.TransactionEvaluationKCApplication",
                List.of("SpringBootApplication", "ComponentScan"),
                List.of(), List.of(), List.of(), List.of(),
                false, null, null, List.of(), List.of());

        Map<String, String> pomHints = Map.of(
                "com.quotient.platform.transaction.eval.TransactionEvaluationKCApplication",
                "transaction-eval-consumer",
                "transaction-eval-consumer",
                "com.quotient.platform.transaction.eval.TransactionEvaluationKCApplication");

        List<FactBatch.EntryTriggerFact> facts = extractor.extract(
                List.of(main), Map.of(main.filePath(), source), "dev", pomHints);

        assertThat(facts).hasSize(1);
        FactBatch.EntryTriggerFact fact = facts.get(0);
        assertThat(fact.triggerKind()).isEqualTo("SPRING_BOOT_MAIN");
        assertThat(fact.linkedHandlerFqn())
                .isEqualTo("com.quotient.platform.transaction.eval.TransactionEvaluationKCApplication");
        assertThat(fact.linkedMethod()).isEqualTo("main");
        assertThat(fact.pathPattern()).isEqualTo("/deploy/transaction-eval-consumer");
        assertThat(fact.attributes()).contains("wiring_only");
        assertThat(fact.evidenceSource()).contains("POM");
    }

    @Test
    void skipsEvaluationJobsModule() {
        String source = """
                @SpringBootApplication
                public class StcRetryJobApplication {
                    public static void main(String[] args) {
                        SpringApplication.run(StcRetryJobApplication.class, args);
                    }
                }
                """;

        ParsedModel jobMain = ParsedModel.of(
                "evaluation-jobs/stc-retry-job/src/main/java/com/quotient/platform/stcretryjob/StcRetryJobApplication.java",
                "com.quotient.platform.stcretryjob.StcRetryJobApplication",
                List.of("SpringBootApplication"),
                List.of(), List.of(), List.of(), List.of(),
                false, null, null, List.of(), List.of());

        List<FactBatch.EntryTriggerFact> facts = extractor.extract(
                List.of(jobMain), Map.of(jobMain.filePath(), source), "dev", Map.of());

        assertThat(facts).isEmpty();
    }

    @Test
    void skipsCommandLineRunnerBatchJobs() {
        String source = """
                @SpringBootApplication
                public class RetryJobApplication implements CommandLineRunner {
                    public static void main(String[] args) {
                        SpringApplication.run(RetryJobApplication.class, args);
                    }
                }
                """;

        ParsedModel batch = ParsedModel.of(
                "jobs/retry-job/src/main/java/com.example.RetryJobApplication.java",
                "com.example.RetryJobApplication",
                List.of("SpringBootApplication"),
                List.of(), List.of(), List.of(), List.of(),
                false, null, null, List.of(), List.of());

        List<FactBatch.EntryTriggerFact> facts = extractor.extract(
                List.of(batch), Map.of(batch.filePath(), source), "dev", Map.of());

        assertThat(facts).isEmpty();
    }
}
