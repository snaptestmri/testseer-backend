package io.testseer.backend.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EntryTriggerScopeFilterTest {

    @Test
    void matchesKafkaTriggerUnderConsumerSourceRoot() {
        EntryFlowService.EntryTriggerView trigger = new EntryFlowService.EntryTriggerView(
                "kafka:QUOT.SALES.TRANSACTION.PIPELINE.EVENTS:com.example.Consumer",
                "KAFKA_SUBSCRIBE",
                "INBOUND",
                "dev",
                "kafka",
                "INTERNAL",
                null,
                "QUOT.SALES.TRANSACTION.PIPELINE.EVENTS",
                "com.example.TransactionEvalConsumer",
                "processSalesCanonicalEvent",
                null,
                "evaluation-consumers/transaction-eval-consumer/src/main/java/com/example/TransactionEvalConsumer.java",
                "JAVA_ANNOTATION",
                0.95
        );

        boolean matches = EntryTriggerScopeFilter.matches(
                trigger,
                EntryTriggerScopeFilter.normalizeRoots(
                        List.of("evaluation-consumers/transaction-eval-consumer/src/main/java")));

        assertThat(matches).isTrue();
    }

    @Test
    void excludesCronManifestOutsideModuleRoots() {
        EntryFlowService.EntryTriggerView cron = new EntryFlowService.EntryTriggerView(
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
                0.90
        );

        List<EntryFlowService.EntryTriggerView> filtered = EntryTriggerScopeFilter.filter(
                List.of(cron),
                EntryTriggerScopeFilter.normalizeRoots(
                        List.of("evaluation-consumers/transaction-eval-consumer/src/main/java")));

        assertThat(filtered).isEmpty();
    }
}
