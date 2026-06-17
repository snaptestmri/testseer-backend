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

class MessagingFlowIntegrationTest extends AbstractIntegrationTest {
    @Autowired MessagingFlowService flowService;
    @Autowired EntryFlowService entryFlowService;
    @Autowired ServiceRegistryService registryService;
    @Autowired io.testseer.backend.ingestion.DualWriteService dualWriteService;
    @Autowired JdbcClient db;

    static final String ORG = "quotient";

    @BeforeEach
    void clean() {
        IntegrationTestDb.clearCoreFacts(db);
    }

    @Test
    void traceTopicFlow_reportsGapsWhenSchemaMissing() {
        String publisherId = register("optimus-offer-services-suite", "optimus-offer-services-suite");
        seedMessagingFacts(publisherId, "optimus-offer-services-suite", true, false);

        MessagingFlowService.EventFlowReport report =
                flowService.traceTopicFlow(publisherId, "PDN_T.RIQ_OFFER_EVENT", "pdn");

        assertThat(report.gaps()).anyMatch(g -> "MISSING_SCHEMA".equals(g.gapType()));
    }

    @Test
    void traceTopicFlow_enrichesFirstStepWithInboundTriggers() {
        String subSvc = register("riq-partner-adapter-suite", "riq-partner-adapter-suite");
        seedSubscriberFacts(subSvc, "riq-partner-adapter-suite");

        MessagingFlowService.EventFlowReport report =
                flowService.traceTopicFlow(subSvc, "PDN_S.RIQ_OFFER_EVENT", "pdn");

        assertThat(report.steps()).isNotEmpty();
        assertThat(report.steps().get(0).handler()).isEqualTo("com.example.RiqOfferEventConsumer");
        assertThat(report.steps().get(0).inboundTriggers()).singleElement().satisfies(trigger -> {
            assertThat(trigger.triggerKind()).isEqualTo("PUBSUB_SUBSCRIBE");
            assertThat(trigger.linkedHandlerFqn()).isEqualTo("com.example.RiqOfferEventConsumer");
            assertThat(trigger.pathPattern()).isEqualTo("PDN_S.RIQ_OFFER_EVENT");
        });
    }

    @Test
    void entryFlowImpact_findsTriggersByExactHandlerFqn() {
        String subSvc = register("riq-partner-adapter-suite", "riq-partner-adapter-suite");
        seedSubscriberFacts(subSvc, "riq-partner-adapter-suite");

        EntryFlowService.EntryTriggerImpactReport report = entryFlowService.impactByHandler(
                ORG, "com.example.RiqOfferEventConsumer", null, "pdn");

        assertThat(report.triggers()).singleElement().satisfies(hit -> {
            assertThat(hit.matchKind()).isEqualTo("EXACT");
            assertThat(hit.serviceId()).isEqualTo(subSvc);
            assertThat(hit.trigger().triggerKind()).isEqualTo("PUBSUB_SUBSCRIBE");
        });
    }

    @Test
    void entryFlowImpact_simpleNameFallbackOrgWide() {
        String subSvc = register("riq-partner-adapter-suite", "riq-partner-adapter-suite");
        seedSubscriberFacts(subSvc, "riq-partner-adapter-suite");

        EntryFlowService.EntryTriggerImpactReport report = entryFlowService.impactByHandler(
                ORG, "wrong.package.RiqOfferEventConsumer", null, "pdn");

        assertThat(report.triggers()).singleElement().satisfies(hit -> {
            assertThat(hit.matchKind()).isEqualTo("SIMPLE_NAME");
            assertThat(hit.trigger().linkedHandlerFqn()).isEqualTo("com.example.RiqOfferEventConsumer");
        });
    }

    @Test
    void traceCrossRepo_enrichesFirstSubscriberWithInboundTriggers() {
        String pubSvc = register("optimus-offer-services-suite", "optimus-offer-services-suite");
        String subSvc = register("riq-partner-adapter-suite", "riq-partner-adapter-suite");
        seedMessagingFacts(pubSvc, "optimus-offer-services-suite", true, true);
        seedSubscriberFacts(subSvc, "riq-partner-adapter-suite");

        MessagingFlowService.CrossRepoFlowReport report =
                flowService.traceCrossRepo(ORG, "PDN_T.RIQ_OFFER_EVENT", "pdn", 5);

        assertThat(report.hops()).isNotEmpty();
        assertThat(report.hops().get(0).subscribers()).isNotEmpty();
        assertThat(report.hops().get(0).subscribers().get(0).inboundTriggers()).singleElement()
                .satisfies(trigger -> assertThat(trigger.triggerKind()).isEqualTo("PUBSUB_SUBSCRIBE"));
    }

    @Test
    void traceCrossRepo_linksPublisherAndSubscriberAcrossServices() {
        String pubSvc = register("optimus-offer-services-suite", "optimus-offer-services-suite");
        String subSvc = register("riq-partner-adapter-suite", "riq-partner-adapter-suite");
        seedMessagingFacts(pubSvc, "optimus-offer-services-suite", true, true);
        seedSubscriberFacts(subSvc, "riq-partner-adapter-suite");

        MessagingFlowService.CrossRepoFlowReport report =
                flowService.traceCrossRepo(ORG, "PDN_T.RIQ_OFFER_EVENT", "pdn", 5);

        assertThat(report.hops()).isNotEmpty();
        assertThat(report.hops().get(0).topicShortId()).isEqualTo("PDN_T.RIQ_OFFER_EVENT");
        assertThat(report.indexedServiceIds()).contains(pubSvc, subSvc);
    }

    @Test
    void traceCrossRepo_enrichesSubscriberWithConsistencyHints() {
        String pubSvc = register("optimus-offer-services-suite", "optimus-offer-services-suite");
        String subSvc = register("riq-partner-adapter-suite", "riq-partner-adapter-suite");
        seedMessagingFacts(pubSvc, "optimus-offer-services-suite", true, true);
        seedSubscriberFacts(subSvc, "riq-partner-adapter-suite");
        seedConsistencyScenario(subSvc, "riq-partner-adapter-suite");

        MessagingFlowService.CrossRepoFlowReport report =
                flowService.traceCrossRepo(ORG, "PDN_T.RIQ_OFFER_EVENT", "pdn", 5);

        assertThat(report.hops()).isNotEmpty();
        assertThat(report.hops().get(0).subscribers()).isNotEmpty();
        assertThat(report.hops().get(0).subscribers().get(0).consistencyHints())
                .anyMatch(h -> "DUAL_WRITE_SAME_HANDLER".equals(h.pattern()));
        assertThat(report.consistencyHints())
                .anyMatch(h -> "DUAL_WRITE_SAME_HANDLER".equals(h.pattern()));
    }

    @Test
    void queryPubSubForOrg_returnsTopicsAcrossServices() {
        String pubSvc = register("optimus-offer-services-suite", "optimus-offer-services-suite");
        String subSvc = register("riq-partner-adapter-suite", "riq-partner-adapter-suite");
        seedMessagingFacts(pubSvc, "optimus-offer-services-suite", true, true);
        seedSubscriberFacts(subSvc, "riq-partner-adapter-suite");

        List<MessagingFlowService.PubSubOrgView> orgTopics =
                flowService.queryPubSubForOrg(ORG, "pdn");

        assertThat(orgTopics).isNotEmpty();
        assertThat(orgTopics).anyMatch(p -> "TOPIC".equals(p.resourceKind())
                && "PDN_T.RIQ_OFFER_EVENT".equals(p.shortId()));
        assertThat(orgTopics).anyMatch(p -> "SUBSCRIPTION".equals(p.resourceKind())
                && "PDN_S.RIQ_OFFER_EVENT".equals(p.shortId()));
    }

    @Test
    void traceCrossRepo_doesNotReportCatalogLibraryWhenRegisteredByServiceName() {
        registryService.register(new RegistrationRequest(
                ORG, "optimus-platform-framework", "platform-data", "MAVEN", "library",
                List.of("platform-data/src/main/java"), List.of(), null));

        MessagingFlowService.CrossRepoFlowReport report =
                flowService.traceCrossRepo(ORG, "PDN_T.RIQ_OFFER_EVENT", "pdn", 1, "quotient-full");

        assertThat(report.missingBundleRepos()).doesNotContain("platform-data");
    }

    @Test
    void traceCrossRepo_detectsMissingBundleRepos() {
        String pubSvc = register("optimus-offer-services-suite", "optimus-offer-services-suite");
        seedMessagingFacts(pubSvc, "optimus-offer-services-suite", true, true);

        MessagingFlowService.CrossRepoFlowReport report =
                flowService.traceCrossRepo(ORG, "PDN_T.RIQ_OFFER_EVENT", "pdn", 3);

        assertThat(report.missingBundleRepos()).contains("optimus-platform-msg-framework");
    }

    @Test
    void traceCrossRepo_linksTerminalRetryHopViaDlqAndCronJob() {
        String pubSvc = register("optimus-offer-services-suite", "optimus-offer-services-suite");
        String argocdSvc = register("platform-argocd-manifest", "platform-argocd-manifest");
        seedRetryTopicPublisher(pubSvc, "optimus-offer-services-suite");
        seedAsyncRetryPath(pubSvc, "optimus-offer-services-suite");
        seedCronTrigger(argocdSvc, "platform-argocd-manifest");

        MessagingFlowService.CrossRepoFlowReport report =
                flowService.traceCrossRepo(ORG, "PDN_T.ACTIVATE_OFFER_RETRY", "pdn", 1);

        assertThat(report.hops()).singleElement().satisfies(hop -> {
            assertThat(hop.topicShortId()).isEqualTo("PDN_T.ACTIVATE_OFFER_RETRY");
            assertThat(hop.terminalContinuations()).isNotEmpty();
        });
        assertThat(report.gaps()).noneMatch(g -> "NO_SUBSCRIBER".equals(g.gapType()));
        assertThat(report.gaps()).anyMatch(g -> "TERMINAL_BATCH_RETRY".equals(g.gapType()));
    }

    @Test
    void traceCrossRepo_runtimeSkipsManifestSubscriberFanOut() {
        String pubSvc = register("optimus-offer-services-suite", "optimus-offer-services-suite");
        String manifestSvc = register("platform-argocd-manifest", "platform-argocd-manifest");
        seedMessagingFacts(pubSvc, "optimus-offer-services-suite", true, true);
        seedManifestYamlSubscriberWithAstraPublish(manifestSvc, "platform-argocd-manifest");

        MessagingFlowService.CrossRepoFlowReport runtime = flowService.traceCrossRepo(
                ORG, "PDN_T.RIQ_OFFER_EVENT", "pdn", 8, null, false, false,
                CrossRepoFollowPolicy.MODE_RUNTIME, false);
        MessagingFlowService.CrossRepoFlowReport inventory = flowService.traceCrossRepo(
                ORG, "PDN_T.RIQ_OFFER_EVENT", "pdn", 8, null, false, false,
                CrossRepoFollowPolicy.MODE_INVENTORY, false);

        assertThat(runtime.followMode()).isEqualTo("runtime");
        assertThat(runtime.hops().stream().map(MessagingFlowService.CrossRepoHop::topicShortId))
                .noneMatch(t -> t.contains("ASTRA"));
        assertThat(runtime.skippedExpansionCount()).isGreaterThanOrEqualTo(1);
        assertThat(inventory.followMode()).isEqualTo("inventory");
        assertThat(inventory.hops().stream().map(MessagingFlowService.CrossRepoHop::topicShortId))
                .anyMatch(t -> t.contains("ASTRA"));
    }

    @Test
    void traceEntryFlow_withMessagingAndCrossRepo() {
        String pubSvc = register("optimus-offer-services-suite", "optimus-offer-services-suite");
        String subSvc = register("riq-partner-adapter-suite", "riq-partner-adapter-suite");
        seedMessagingFacts(pubSvc, "optimus-offer-services-suite", true, true);
        seedSubscriberFacts(subSvc, "riq-partner-adapter-suite");

        EntryFlowService.EntryFlowReport report = entryFlowService.traceEntryFlow(
                subSvc,
                "pubsub:pdn_s.riq_offer_event:com.example.riqoffereventconsumer",
                null,
                "pdn",
                true,
                false,
                true,
                ORG,
                5);

        assertThat(report.steps()).singleElement().satisfies(step -> {
            assertThat(step.trigger().triggerKind()).isEqualTo("PUBSUB_SUBSCRIBE");
            assertThat(step.trigger().linkedHandlerFqn()).isEqualTo("com.example.RiqOfferEventConsumer");
        });
        assertThat(report.messagingTopicShortId()).isEqualTo("PDN_T.RIQ_OFFER_EVENT");
        assertThat(report.messagingFlow()).isNotNull();
        assertThat(report.messagingFlow().steps()).isNotEmpty();
        assertThat(report.crossRepoFlow()).isNotNull();
        assertThat(report.crossRepoFlow().hops()).isNotEmpty();
        assertThat(report.externalEndpoints()).isEmpty();
    }

    @Test
    void traceEntryFlow_defaultOmitsMessagingChain() {
        String subSvc = register("riq-partner-adapter-suite", "riq-partner-adapter-suite");
        seedSubscriberFacts(subSvc, "riq-partner-adapter-suite");

        EntryFlowService.EntryFlowReport report = entryFlowService.traceEntryFlow(
                subSvc,
                "pubsub:pdn_s.riq_offer_event:com.example.riqoffereventconsumer",
                null,
                "pdn");

        assertThat(report.steps()).isNotEmpty();
        assertThat(report.messagingTopicShortId()).isNull();
        assertThat(report.messagingFlow()).isNull();
        assertThat(report.crossRepoFlow()).isNull();
    }

    @Test
    void traceTopicFlow_includesPubSubFactsRegardlessOfEnvParam() {
        String svc = register("transaction-eval-suite", "transaction-eval-suite");
        var pubsub = List.of(new FactBatch.PubSubResourceFact(
                "TOPIC", "QUOT.SALES.TRANSACTION.PROCESSED.EVENTS", "unknown", "base", null, null,
                "PUBLISH", "kafka.topics.stxn.processed.topic-name", "application.yaml",
                "transaction-eval-consumer",
                "com.quotient.platform.transaction.eval.producer.StxnProcessedEventProducer",
                "publishEvent", "transaction-eval-consumer-ns",
                "RULE_PACK", 0.97, "{\"transport\":\"KAFKA\"}"));

        FactBatch batch = FactBatch.create(
                "job-kafka-1", ORG, "platform-transaction-eval-consumer", svc, "abc123", "DELTA",
                List.of(), List.of(), List.of(), List.of(),
                pubsub, List.of(), List.of(), List.of(), List.of());
        dualWriteService.write(batch, List.of());

        MessagingFlowService.EventFlowReport report =
                flowService.traceTopicFlow(svc, "QUOT.SALES.TRANSACTION.PROCESSED.EVENTS", "dev");

        assertThat(report.pubsubResources()).isNotEmpty();
        assertThat(report.steps()).isNotEmpty();
        assertThat(report.steps().get(0).outbounds()).isNotEmpty();
    }

    private String register(String repo, String serviceId) {
        return registryService.register(new RegistrationRequest(
                ORG, repo, repo, "MAVEN", "service",
                List.of("src/main/java"), List.of("src/test/java"), null
        )).serviceId();
    }

    private void seedMessagingFacts(String serviceId, String repo, boolean withPublisher, boolean withSchema) {
        var pubsub = List.of(new FactBatch.PubSubResourceFact(
                "TOPIC", "PDN_T.RIQ_OFFER_EVENT", "pdn", "pdn", "prj-test", null,
                "PUBLISH", "offer-ingestion", "application-pdn.yaml", "offer-ingestion",
                "com.example.OfferIngestionPublisher", "publish", "offer-ingestion-ns",
                "YAML", 1.0, null));

        var schemas = withSchema ? List.of(new FactBatch.MessageSchemaFact(
                "QMsgEvent", "RIQOfferEvent.Offer", "[{\"name\":\"offer_id\"}]", null,
                "com.example.OfferIngestionPublisher", "publish", "OUTBOUND",
                "PDN_T.RIQ_OFFER_EVENT", null, "RIQOfferEvent.proto", "PROTO", 1.0)) : List.<FactBatch.MessageSchemaFact>of();

        var gates = List.of(new FactBatch.FlowGateFact(
                "pdn", "com.example.OfferIngestionPublisher", "RIQ_EVENT", null,
                "CODE_FLAG", "pubsub.enabled", "true", "EQ", "NO_PUBLISH", null,
                "Enable pubsub", "YAML", "application-pdn.yaml", 0.9));

        FactBatch batch = FactBatch.create(
                "job-msg-1", ORG, repo, serviceId, "abc123", "DELTA",
                List.of(), List.of(), List.of(), List.of(),
                pubsub, schemas, List.of(), gates, List.of());

        dualWriteService.write(batch, List.of());
        if (withPublisher) {
            // graph projection for messaging happens in orchestrator; invoke projector via re-index path
        }
    }

    private void seedSubscriberFacts(String serviceId, String repo) {
        String handler = "com.example.RiqOfferEventConsumer";
        var pubsub = List.of(new FactBatch.PubSubResourceFact(
                "SUBSCRIPTION", "PDN_S.RIQ_OFFER_EVENT", "pdn", "pdn", null, null,
                "SUBSCRIBE", "riq-offer-event", "application-pdn.yaml", "offer-events-consumer",
                handler, "onMessage", "offer-events-consumer-ns",
                "YAML", 1.0, null));

        var entryTriggers = List.of(new FactBatch.EntryTriggerFact(
                "pubsub:pdn_s.riq_offer_event:com.example.riqoffereventconsumer",
                "PUBSUB_SUBSCRIBE", "INBOUND", "pdn", "pubsub", "INTERNAL",
                null, "PDN_S.RIQ_OFFER_EVENT",
                handler, "onMessage",
                null, "riq-offer-event", "PUBSUB_LINK", 1.0, null));

        FactBatch batch = FactBatch.create(
                "job-msg-2", ORG, repo, serviceId, "def456", "DELTA",
                List.of(), List.of(), List.of(), List.of(),
                pubsub, List.of(), List.of(), List.of(), List.of())
                .withEntryTriggers(entryTriggers);

        dualWriteService.write(batch, List.of());
    }

    private void seedConsistencyScenario(String serviceId, String repo) {
        String handler = "com.example.RiqOfferEventConsumer";
        var dataAccess = List.of(new FactBatch.DataAccessFact(
                handler, "onMessage", "WRITE", "MARIADB", "PartnerOfferCallRecorder",
                "dao", "saveToDb", "[]", null, "TEST", 1.0,
                null, null, null, null, null, null));

        var scenarios = List.of(new FactBatch.ConsistencyScenarioFact(
                "partner-recorder-dual-write",
                "DUAL_WRITE_SAME_HANDLER",
                "HANDLER",
                handler + "#onMessage",
                "MARIADB",
                "PartnerOfferCallRecorder",
                "[]",
                "[]",
                "{}",
                "[]",
                "INFERRED",
                0.9,
                null));

        FactBatch batch = FactBatch.create(
                "job-con-1", ORG, repo, serviceId, "ghi789", "DELTA",
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), dataAccess, List.of(), List.of())
                .withConsistencyScenarios(scenarios);

        dualWriteService.write(batch, List.of());
    }

    private void seedRetryTopicPublisher(String serviceId, String repo) {
        var pubsub = List.of(new FactBatch.PubSubResourceFact(
                "TOPIC", "PDN_T.ACTIVATE_OFFER_RETRY", "pdn", "pdn", "prj-test", null,
                "PUBLISH", "activate-retry", "application-pdn.yaml", "optimus-pao-ns",
                "com.example.ActivationRetryEventPublisher", "publish", "optimus-pao-ns",
                "YAML", 1.0, null));
        FactBatch batch = FactBatch.create(
                "job-retry-pub", ORG, repo, serviceId, "abc123", "DELTA",
                List.of(), List.of(), List.of(), List.of(),
                pubsub, List.of(), List.of(), List.of(), List.of());
        dualWriteService.write(batch, List.of());
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

    private void seedManifestYamlSubscriberWithAstraPublish(String serviceId, String repo) {
        var pubsub = List.of(
                new FactBatch.PubSubResourceFact(
                        "SUBSCRIPTION", "PDN_S.RIQ_OFFER_EVENT", "pdn", "pdn", null, null,
                        "SUBSCRIBE", "manifest-sub", "k8s-manifest.yaml", "manifest-workload",
                        null, null, "manifest-ns",
                        "YAML", 1.0, null),
                new FactBatch.PubSubResourceFact(
                        "TOPIC", "PDN_T.PIPELINE.ASTRA", "pdn", "pdn", null, null,
                        "PUBLISH", "astra-egress", "k8s-manifest.yaml", "manifest-workload",
                        null, null, "manifest-ns",
                        "YAML", 1.0, null));

        FactBatch batch = FactBatch.create(
                "job-manifest-1", ORG, repo, serviceId, "manifest1", "DELTA",
                List.of(), List.of(), List.of(), List.of(),
                pubsub, List.of(), List.of(), List.of(), List.of());

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
