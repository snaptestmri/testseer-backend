package io.testseer.backend.query;

import io.testseer.backend.AbstractIntegrationTest;
import io.testseer.backend.IntegrationTestDb;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalHopEnricherTest extends AbstractIntegrationTest {

    static final String ORG = "quotient";

    @Autowired TerminalHopEnricher enricher;
    @Autowired ServiceRegistryService registryService;
    @Autowired io.testseer.backend.ingestion.DualWriteService dualWriteService;
    @Autowired JdbcClient db;

    @BeforeEach
    void clean() {
        IntegrationTestDb.clearCoreFacts(db);
    }

    @Test
    void enrich_replacesNoSubscriberWithTerminalBatchRetry() {
        String retrySvc = registryService.register(new RegistrationRequest(
                ORG, "optimus-offer-services-suite", "optimus-offer-services-suite", "MAVEN", "service",
                List.of("src/main/java"), List.of(), null)).serviceId();
        String argocdSvc = registryService.register(new RegistrationRequest(
                ORG, "platform-argocd-manifest", "platform-argocd-manifest", "MAVEN", "service",
                List.of("riq/kubernetes-manifests"), List.of(), null)).serviceId();

        seedAsyncRetryPath(retrySvc, "optimus-offer-services-suite");
        seedCronTrigger(argocdSvc, "platform-argocd-manifest");

        MessagingFlowService.CrossRepoHop hop = new MessagingFlowService.CrossRepoHop(
                2, "PDN_T.ACTIVATE_OFFER_RETRY", List.of(), List.of());
        List<MessagingFlowService.FlowGap> gaps = List.of(
                new MessagingFlowService.FlowGap(
                        "NO_SUBSCRIBER", "No subscriber indexed for topic PDN_T.ACTIVATE_OFFER_RETRY"));

        TerminalHopEnricher.Result result = enricher.enrich(ORG, "pdn", List.of(hop), gaps);

        assertThat(result.hops()).singleElement().satisfies(enriched -> {
            assertThat(enriched.terminalContinuations()).isNotEmpty();
            assertThat(enriched.terminalContinuations()).anyMatch(c ->
                    "pao-freedom-retry-job".equals(c.cronJobName())
                            && "freedom".equals(c.partnerVariant()));
        });
        assertThat(result.gaps()).noneMatch(g -> "NO_SUBSCRIBER".equals(g.gapType()));
        assertThat(result.gaps()).anyMatch(g -> "TERMINAL_BATCH_RETRY".equals(g.gapType()));
    }

    private void seedAsyncRetryPath(String serviceId, String repo) {
        FactBatch batch = FactBatch.create(
                "job-dlq-1", ORG, repo, serviceId, "abc123", "DELTA",
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of())
                .withAsyncRetryPaths(List.of(new FactBatch.AsyncRetryPathFact(
                        "pdn", "optimus-pao-freedom-retry-job", null,
                        "PDN_DLQ_RETRY", "ACTIVATE_OFFER_FREEDOM_INT_DLQ",
                        "optimus-pao-freedom-retry-job/src/main/resources/application-pdn.yaml",
                        "YAML_DLQ_RETRY", 0.9, null)));
        dualWriteService.write(batch, List.of());
    }

    private void seedCronTrigger(String serviceId, String repo) {
        FactBatch batch = FactBatch.create(
                "job-cron-1", ORG, repo, serviceId, "abc123", "DELTA",
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of())
                .withEntryTriggers(List.of(new FactBatch.EntryTriggerFact(
                        "k8s-cron:pao-freedom-retry-job", "CRON_K8S", "INBOUND", "pdn",
                        "kubernetes", "INTERNAL", null, "/cronjob/pao-freedom-retry-job",
                        null, null, null,
                        "riq/kubernetes-manifests/.../cronjob-patch.yaml",
                        "K8S_MANIFEST", 0.9,
                        "{\"cronJob\":\"pao-freedom-retry-job\",\"schedule\":\"30 */4 * * *\"}")));
        dualWriteService.write(batch, List.of());
    }
}
