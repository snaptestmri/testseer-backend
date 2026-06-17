package io.testseer.backend.ingestion.triggers;

import io.testseer.backend.config.TriggerRulePack;
import io.testseer.backend.config.TriggerRulePackLoader;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.ParsedModel;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CronHandlerLinkerTest {

    private final CronHandlerLinker linker = new CronHandlerLinker(
            new TriggerRulePackLoader(new ByteArrayResource(
                    "cronHandlerLinks: []\n".getBytes(StandardCharsets.UTF_8))));

    @Test
    void linksCronJobToSpringBootLauncherInEvaluationJobsModule() {
        String launcherSource = """
                package com.quotient.platform.stcretryjob;
                import org.springframework.boot.SpringApplication;
                public class StcRetryJobApplication {
                    public static void main(String[] args) {
                        SpringApplication.run(StcRetryJobApplication.class, args);
                    }
                }
                """;

        ParsedModel launcher = ParsedModel.of(
                "evaluation-jobs/stc-retry-job/src/main/java/com/quotient/platform/stcretryjob/StcRetryJobApplication.java",
                "com.quotient.platform.stcretryjob.StcRetryJobApplication",
                List.of(), List.of(), List.of(), List.of(), List.of(),
                false, null, null, List.of(), List.of());

        FactBatch.EntryTriggerFact cron = new FactBatch.EntryTriggerFact(
                "k8s-cron:stc-retry-job",
                "CRON_K8S",
                "INBOUND",
                "dev",
                "kubernetes",
                "INTERNAL",
                null,
                "/cronjob/stc-retry-job",
                null,
                null,
                null,
                "evaluation-jobs/stc-retry-job/kubernetes-manifests/dev/stc-retry-batch-job.cronjob.yaml",
                "K8S_MANIFEST",
                0.90,
                "{\"cronJob\":\"stc-retry-job\",\"schedule\":\"0 12 * * *\"}"
        );

        List<FactBatch.EntryTriggerFact> linked = linker.link(
                List.of(cron),
                List.of(launcher),
                Map.of(launcher.filePath(), launcherSource));

        assertThat(linked).hasSize(1);
        assertThat(linked.get(0).linkedHandlerFqn())
                .isEqualTo("com.quotient.platform.stcretryjob.StcRetryJobApplication");
        assertThat(linked.get(0).linkedMethod()).isEqualTo("main");
        assertThat(linked.get(0).evidenceSource()).contains("JAVA_LAUNCHER");
    }

    @Test
    void linksCronJobViaNestedJobModulePath() {
        String launcherSource = """
                package com.quotient.platform.platformeventretryjob;
                import org.springframework.boot.SpringApplication;
                public class PlatformEventRetryJobApplication {
                    public static void main(String[] args) {
                        SpringApplication.run(PlatformEventRetryJobApplication.class, args);
                    }
                }
                """;

        ParsedModel launcher = ParsedModel.of(
                "platform-event-retry-job/src/main/java/com/quotient/platform/platformeventretryjob/PlatformEventRetryJobApplication.java",
                "com.quotient.platform.platformeventretryjob.PlatformEventRetryJobApplication",
                List.of(), List.of(), List.of(), List.of(), List.of(),
                false, null, null, List.of(), List.of());

        FactBatch.EntryTriggerFact cron = new FactBatch.EntryTriggerFact(
                "k8s-cron:platform-event-retry-job",
                "CRON_K8S",
                "INBOUND",
                "qa",
                "kubernetes",
                "INTERNAL",
                null,
                "/cronjob/platform-event-retry-job",
                null,
                null,
                null,
                "platform-event-retry-job/kubernetes-manifests/qa/platform-event-retry-job.qa.cronjob.yaml",
                "K8S_MANIFEST",
                0.90,
                "{\"cronJob\":\"platform-event-retry-job\"}"
        );

        List<FactBatch.EntryTriggerFact> linked = linker.link(
                List.of(cron),
                List.of(launcher),
                Map.of(launcher.filePath(), launcherSource));

        assertThat(linked.get(0).linkedHandlerFqn())
                .isEqualTo("com.quotient.platform.platformeventretryjob.PlatformEventRetryJobApplication");
    }

    @Test
    void linksCronJobViaLauncherClassNameWhenPathHasNoJobFolder() {
        String launcherSource = """
                package com.quotient.platform.genericpaymentretryjob;
                import org.springframework.boot.SpringApplication;
                public class GenericPaymentRetryJobApplication {
                    public static void main(String[] args) {
                        SpringApplication.run(GenericPaymentRetryJobApplication.class, args);
                    }
                }
                """;

        ParsedModel launcher = ParsedModel.of(
                "src/main/java/com/quotient/platform/genericpaymentretryjob/GenericPaymentRetryJobApplication.java",
                "com.quotient.platform.genericpaymentretryjob.GenericPaymentRetryJobApplication",
                List.of(), List.of(), List.of(), List.of(), List.of(),
                false, null, null, List.of(), List.of());

        FactBatch.EntryTriggerFact cron = new FactBatch.EntryTriggerFact(
                "k8s-cron:generic-payment-retry-job",
                "CRON_K8S",
                "INBOUND",
                "dev",
                "kubernetes",
                "INTERNAL",
                null,
                "/cronjob/generic-payment-retry-job",
                null,
                null,
                null,
                "kubernetes-manifests/dev/generic-payment-retry-job.cronjob.yaml",
                "K8S_MANIFEST",
                0.90,
                "{\"cronJob\":\"generic-payment-retry-job\"}"
        );

        List<FactBatch.EntryTriggerFact> linked = linker.link(
                List.of(cron),
                List.of(launcher),
                Map.of(launcher.filePath(), launcherSource));

        assertThat(linked.get(0).linkedHandlerFqn())
                .isEqualTo("com.quotient.platform.genericpaymentretryjob.GenericPaymentRetryJobApplication");
    }

    @Test
    void leavesUnlinkedCronWhenNoMatchingLauncher() {
        FactBatch.EntryTriggerFact cron = new FactBatch.EntryTriggerFact(
                "k8s-cron:unknown-job",
                "CRON_K8S",
                "INBOUND",
                "dev",
                "kubernetes",
                "INTERNAL",
                null,
                "/cronjob/unknown-job",
                null,
                null,
                null,
                "kubernetes-manifests/unknown.cronjob.yaml",
                "K8S_MANIFEST",
                0.90,
                "{\"cronJob\":\"unknown-job\"}"
        );

        List<FactBatch.EntryTriggerFact> linked = linker.link(List.of(cron), List.of(), Map.of());
        assertThat(linked.get(0).linkedHandlerFqn()).isNull();
    }
}
