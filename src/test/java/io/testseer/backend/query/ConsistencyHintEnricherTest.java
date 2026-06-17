package io.testseer.backend.query;

import io.testseer.backend.config.MessagingRulePack;
import io.testseer.backend.config.MessagingRulePackLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConsistencyHintEnricherTest {

    @Mock ConsistencyQueryService consistencyQueryService;
    @Mock MessagingRulePackLoader rulePackLoader;
    @Mock CrossRepoGateLinker crossRepoGateLinker;
    @Mock InvariantDeriver invariantDeriver;
    @Mock BatchIngestHintEnricher batchIngestHintEnricher;
    @Mock PipelineEndStateEnricher pipelineEndStateEnricher;
    @Mock PropagationLagEnricher propagationLagEnricher;

    ConsistencyHintEnricher enricher;

    static final String SUB_SVC = "sub-service";
    static final String HANDLER = "com.example.RiqOfferEventConsumer";

    @BeforeEach
    void setUp() {
        when(rulePackLoader.getRulePack()).thenReturn(MessagingRulePack.empty());
        when(crossRepoGateLinker.attachDownstreamGates(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(invariantDeriver.derive(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        when(batchIngestHintEnricher.maybeAttach(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(4));
        when(propagationLagEnricher.enrichHops(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));
        enricher = new ConsistencyHintEnricher(
                consistencyQueryService, rulePackLoader, crossRepoGateLinker,
                invariantDeriver, batchIngestHintEnricher, pipelineEndStateEnricher,
                propagationLagEnricher);
    }

    @Test
    void enrichCrossRepoHops_attachesHintsToMatchingSubscriber() {
        when(consistencyQueryService.query(eq(SUB_SVC), isNull(), isNull()))
                .thenReturn(List.of(scenario(
                        "partner-recorder-dual-write",
                        "DUAL_WRITE_SAME_HANDLER",
                        HANDLER + "#onMessage",
                        "PartnerOfferCallRecorder")));

        MessagingFlowService.PubSubOrgView subscriber = pubSub(SUB_SVC, HANDLER);
        MessagingFlowService.CrossRepoHop hop = new MessagingFlowService.CrossRepoHop(
                1, "PDN_T.RIQ_OFFER_EVENT", List.of(), List.of(subscriber));

        List<MessagingFlowService.DataAccessView> writes = List.of(dataAccess(
                HANDLER, "onMessage", "WRITE", "PartnerOfferCallRecorder"));

        List<MessagingFlowService.CrossRepoHop> enriched = enricher.enrichCrossRepoHops(
                List.of(hop),
                (serviceId, handlerFqn) -> writes);

        assertThat(enriched).singleElement().satisfies(h -> {
            assertThat(h.subscribers()).singleElement().satisfies(sub -> {
                assertThat(sub.consistencyHints()).singleElement().satisfies(hint -> {
                    assertThat(hint.scenarioId()).isEqualTo("partner-recorder-dual-write");
                    assertThat(hint.pattern()).isEqualTo("DUAL_WRITE_SAME_HANDLER");
                });
            });
        });
    }

    @Test
    void enrichSteps_doesNotAttachCoTableHintOnReadOnlyPrimary() {
        when(rulePackLoader.getRulePack()).thenReturn(packWithCoTableRule());
        enricher = new ConsistencyHintEnricher(
                consistencyQueryService, rulePackLoader, crossRepoGateLinker,
                invariantDeriver, batchIngestHintEnricher, pipelineEndStateEnricher,
                propagationLagEnricher);

        when(consistencyQueryService.query(eq("galo-svc"), isNull(), isNull()))
                .thenReturn(List.of(rulePackScenario(
                        "offer-pidmap-gate",
                        "CO_TABLE_INVARIANT",
                        "FLOW_STEP",
                        "Offer,OfferPidMap",
                        "[{\"physicalName\":\"Offer\",\"role\":\"PRIMARY\"},"
                                + "{\"physicalName\":\"OfferPidMap\",\"role\":\"REQUIRED_CHILD\"}]",
                        "[{\"kind\":\"ROW_EXISTS\",\"description\":\"gate\"}]")));

        MessagingFlowService.EventFlowStep step = new MessagingFlowService.EventFlowStep(
                1, "galo", "com.example.GaloReader",
                List.of(dataAccess("com.example.GaloReader", "read", "READ", "Offer")),
                List.of(),
                List.of(), null, List.of(), List.of(), List.of(), List.of(), List.of());

        List<MessagingFlowService.EventFlowStep> enriched =
                enricher.enrichSteps("galo-svc", List.of(step));

        assertThat(enriched.get(0).consistencyHints()).isEmpty();
    }

    @Test
    void enrichSteps_attachesCoTableHintWhenBothTablesWritten() {
        when(rulePackLoader.getRulePack()).thenReturn(packWithCoTableRule());
        enricher = new ConsistencyHintEnricher(
                consistencyQueryService, rulePackLoader, crossRepoGateLinker,
                invariantDeriver, batchIngestHintEnricher, pipelineEndStateEnricher,
                propagationLagEnricher);

        when(consistencyQueryService.query(eq("adapter-svc"), isNull(), isNull()))
                .thenReturn(List.of(rulePackScenario(
                        "offer-pidmap-gate",
                        "CO_TABLE_INVARIANT",
                        "FLOW_STEP",
                        "Offer,OfferPidMap",
                        "[{\"physicalName\":\"Offer\",\"role\":\"PRIMARY\"},"
                                + "{\"physicalName\":\"OfferPidMap\",\"role\":\"REQUIRED_CHILD\"}]",
                        "[{\"kind\":\"ROW_EXISTS\",\"description\":\"gate\"}]")));

        MessagingFlowService.EventFlowStep step = new MessagingFlowService.EventFlowStep(
                1, "hyvee", "com.example.HyveeSyncHandler",
                List.of(),
                List.of(
                        dataAccess("com.example.HyveeSyncHandler", "sync", "WRITE", "Offer"),
                        dataAccessWithEntity("com.example.HyveeSyncHandler", "sync", "WRITE",
                                "offer_pid_mapping", "offer_pid_mapping",
                                "com.quotient.platform.data.OfferPidMapEntity")),
                List.of(), null, List.of(), List.of(), List.of(), List.of(), List.of());

        List<MessagingFlowService.EventFlowStep> enriched =
                enricher.enrichSteps("adapter-svc", List.of(step));

        assertThat(enriched.get(0).consistencyHints()).singleElement().satisfies(hint -> {
            assertThat(hint.scenarioId()).isEqualTo("offer-pidmap-gate");
            assertThat(hint.participants()).extracting(ConsistencyParticipantHintView::physicalName)
                    .contains("OfferPidMap");
        });
    }

    @Test
    void enrichCrossRepoHops_attachesDownstreamGatesViaLinker() {
        when(consistencyQueryService.query(eq(SUB_SVC), isNull(), isNull()))
                .thenReturn(List.of(rulePackScenario(
                        "offer-pidmap-gate",
                        "CO_TABLE_INVARIANT",
                        "FLOW_STEP",
                        "Offer",
                        "[{\"physicalName\":\"Offer\",\"role\":\"PRIMARY\"},"
                                + "{\"physicalName\":\"OfferPidMap\",\"role\":\"REQUIRED_CHILD\"}]",
                        "[]")));

        ConsistencyHintView baseHint = ConsistencyHintView.fromScenario(rulePackScenario(
                "offer-pidmap-gate", "CO_TABLE_INVARIANT", "FLOW_STEP", "Offer",
                "[{\"physicalName\":\"Offer\",\"role\":\"PRIMARY\"},"
                        + "{\"physicalName\":\"OfferPidMap\",\"role\":\"REQUIRED_CHILD\"}]",
                "[]"));
        DownstreamGateView downstream = new DownstreamGateView(
                "galo-svc", "galo-repo", 2, "OfferPidMap.IsPublished", "true", "SKIP", "must be published");
        when(crossRepoGateLinker.attachDownstreamGates(org.mockito.ArgumentMatchers.any(), eq(1),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(baseHint.withDownstreamGates(List.of(downstream)));

        MessagingFlowService.PubSubOrgView subscriber = pubSub(SUB_SVC, HANDLER);
        MessagingFlowService.CrossRepoHop hop = new MessagingFlowService.CrossRepoHop(
                1, "PDN_T.RIQ_OFFER_EVENT", List.of(), List.of(subscriber));

        List<MessagingFlowService.DataAccessView> writes = List.of(
                dataAccess(HANDLER, "onMessage", "WRITE", "Offer"),
                dataAccessWithEntity(HANDLER, "onMessage", "WRITE",
                        "offer_pid_mapping", "offer_pid_mapping",
                        "com.quotient.platform.data.OfferPidMapEntity"));

        CrossRepoTraceContext ctx = new CrossRepoTraceContext(
                "quotient", "pdn", List.of(hop), Set.of(SUB_SVC), Map.of(), Map.of(), null);

        List<MessagingFlowService.CrossRepoHop> enriched = enricher.enrichCrossRepoHops(
                List.of(hop),
                (serviceId, handlerFqn) -> writes,
                ctx);

        assertThat(enriched.get(0).subscribers().get(0).consistencyHints()).singleElement()
                .satisfies(h -> assertThat(h.downstreamGates()).hasSize(1));
    }

    @Test
    void enrichCrossRepoHops_leavesSubscriberEmptyWhenNoHandler() {
        MessagingFlowService.PubSubOrgView subscriber = pubSub(SUB_SVC, null);
        MessagingFlowService.CrossRepoHop hop = new MessagingFlowService.CrossRepoHop(
                1, "PDN_T.RIQ_OFFER_EVENT", List.of(), List.of(subscriber));

        List<MessagingFlowService.CrossRepoHop> enriched = enricher.enrichCrossRepoHops(
                List.of(hop),
                (serviceId, handlerFqn) -> List.of());

        assertThat(enriched.get(0).subscribers().get(0).consistencyHints()).isEmpty();
    }

    @Test
    void enrichCrossRepoHops_enrichesPublishersWhenHandlerLinked() {
        when(consistencyQueryService.query(eq("pub-service"), isNull(), isNull()))
                .thenReturn(List.of(scenario(
                        "partner-recorder-dual-write",
                        "DUAL_WRITE_SAME_HANDLER",
                        "com.example.Publisher#publishEvent",
                        "PartnerOfferCallRecorder")));

        MessagingFlowService.PubSubOrgView publisher = pubSub("pub-service", "com.example.Publisher");
        MessagingFlowService.CrossRepoHop hop = new MessagingFlowService.CrossRepoHop(
                1, "PDN_T.RIQ_OFFER_EVENT", List.of(publisher), List.of());

        List<MessagingFlowService.DataAccessView> writes = List.of(dataAccess(
                "com.example.Publisher", "publishEvent", "WRITE", "PartnerOfferCallRecorder"));

        List<MessagingFlowService.CrossRepoHop> enriched = enricher.enrichCrossRepoHops(
                List.of(hop),
                (serviceId, handlerFqn) -> writes);

        assertThat(enriched.get(0).publishers().get(0).consistencyHints()).isNotEmpty();
    }

    @Test
    void enrichCrossRepoHops_expandsDelegateTouchpointsForConsumer() {
        when(rulePackLoader.getRulePack()).thenReturn(packWithHyveeFlowSteps());
        enricher = new ConsistencyHintEnricher(
                consistencyQueryService, rulePackLoader, crossRepoGateLinker,
                invariantDeriver, batchIngestHintEnricher, pipelineEndStateEnricher,
                propagationLagEnricher);

        when(consistencyQueryService.query(eq(SUB_SVC), isNull(), isNull()))
                .thenReturn(List.of(rulePackScenario(
                        "offer-pidmap-gate",
                        "CO_TABLE_INVARIANT",
                        "FLOW_STEP",
                        "Offer",
                        "[{\"physicalName\":\"Offer\",\"role\":\"PRIMARY\"},"
                                + "{\"physicalName\":\"OfferPidMap\",\"role\":\"REQUIRED_CHILD\"}]",
                        "[]")));

        String consumer = "com.quotient.platform.partneradapter.consumer.PartnerAdapterConsumer";
        MessagingFlowService.PubSubOrgView base = pubSub(SUB_SVC, consumer);
        MessagingFlowService.PubSubOrgView subscriber = new MessagingFlowService.PubSubOrgView(
                base.serviceId(), base.repo(), base.serviceName(),
                base.resourceKind(), "PDN_S.PARTNER_ADAPTER_NOTIFICATION", base.envLane(),
                base.role(), base.springKey(), base.fullResourceId(), base.moduleName(),
                consumer, base.workloadName(), base.evidenceSource(), base.confidence(), base.transport(),
                List.of(), base.inboundTriggers(), base.liveVerification());

        MessagingFlowService.CrossRepoHop hop = new MessagingFlowService.CrossRepoHop(
                1, "PDN_T.RIQ_OFFER_EVENT", List.of(), List.of(subscriber));

        List<MessagingFlowService.DataAccessView> adapterWrites = List.of(
                dataAccess("com.example.HyveeOfferAdapter", "recordSubmission", "WRITE", "Offer"),
                dataAccessWithEntity("com.example.HyveeOfferAdapter", "recordSubmission", "WRITE",
                        "offer_pid_mapping", "offer_pid_mapping",
                        "com.quotient.platform.data.OfferPidMapEntity"));

        CrossRepoTraceContext ctx = new CrossRepoTraceContext(
                "quotient", "pdn", List.of(hop), Set.of(SUB_SVC),
                Map.of(SUB_SVC, adapterWrites), Map.of(), null);

        List<MessagingFlowService.CrossRepoHop> enriched = enricher.enrichCrossRepoHops(
                List.of(hop),
                (serviceId, handlerFqn) -> List.of(),
                ctx);

        assertThat(enriched.get(0).subscribers().get(0).consistencyHints()).singleElement()
                .extracting(ConsistencyHintView::scenarioId)
                .isEqualTo("offer-pidmap-gate");
    }

    private static MessagingRulePack packWithHyveeFlowSteps() {
        return new MessagingRulePack(
                List.of(new MessagingRulePack.TopicFlowStepRule("PARTNER_ADAPTER", "HYVEE_ADAPTER")),
                List.of(new MessagingRulePack.ClassFlowStepRule("hyvee", "HYVEE_ADAPTER")),
                Map.of(), List.of(),
                List.of(new MessagingRulePack.ClassFlowStepRule("hyvee", "HYVEE_ADAPTER")),
                List.of(), Map.of(), Map.of(),
                Map.of("offer-pidmap-gate", new MessagingRulePack.ConsistencyRule(
                        "CO_TABLE_INVARIANT",
                        List.of("HYVEE_ADAPTER"),
                        "MARIADB",
                        "Offer",
                        List.of(
                                new MessagingRulePack.ConsistencyParticipantRule(
                                        "MARIADB", "Offer", "PRIMARY", null, null),
                                new MessagingRulePack.ConsistencyParticipantRule(
                                        "MARIADB", "OfferPidMap", "REQUIRED_CHILD", null, null)),
                        List.of(),
                        List.of("offerId", "partnerId"),
                        null, null, List.of())),
                Map.of(), List.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                MessagingRulePack.CrossRepoTraceRule.empty());
    }

    private static MessagingRulePack packWithCoTableRule() {
        return new MessagingRulePack(
                List.of(), List.of(), Map.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(),
                Map.of("offer-pidmap-gate", new MessagingRulePack.ConsistencyRule(
                        "CO_TABLE_INVARIANT",
                        List.of("GALO_READ", "OIS_INGEST"),
                        "MARIADB",
                        "Offer",
                        List.of(
                                new MessagingRulePack.ConsistencyParticipantRule(
                                        "MARIADB", "Offer", "PRIMARY", null, null),
                                new MessagingRulePack.ConsistencyParticipantRule(
                                        "MARIADB", "OfferPidMap", "REQUIRED_CHILD", null, null)),
                        List.of(),
                        List.of("offerId", "partnerId"),
                        null, null, List.of())),
                Map.of(), List.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                MessagingRulePack.CrossRepoTraceRule.empty());
    }

    private static MessagingFlowService.PubSubOrgView pubSub(String serviceId, String handler) {
        return new MessagingFlowService.PubSubOrgView(
                serviceId, "repo", "name", "SUBSCRIPTION", "PDN_S.RIQ_OFFER_EVENT", "pdn",
                "SUBSCRIBE", null, null, "module", handler, "workload", "YAML", 1.0, "PUBSUB", List.of(), List.of(), null);
    }

    private static MessagingFlowService.DataAccessView dataAccess(
            String handler, String method, String operation, String table) {
        return dataAccess(handler, method, operation, table, table);
    }

    private static MessagingFlowService.DataAccessView dataAccess(
            String handler, String method, String operation, String table, String physicalName) {
        return new MessagingFlowService.DataAccessView(
                handler, method, operation, table, "dao", "[]", "TEST", 1.0,
                "MARIADB", null, null, null, null, null, null,
                physicalName, null, null, null, List.of());
    }

    private static MessagingFlowService.DataAccessView dataAccessWithEntity(
            String handler, String method, String operation, String table,
            String physicalName, String entityFqn) {
        return new MessagingFlowService.DataAccessView(
                handler, method, operation, table, "dao", "[]", "TEST", 1.0,
                "MARIADB", entityFqn, null, null, null, null, null,
                physicalName, null, null, null, List.of());
    }

    private static ConsistencyQueryService.ConsistencyScenarioView scenario(
            String id, String pattern, String scopeRef, String physical) {
        return new ConsistencyQueryService.ConsistencyScenarioView(
                id, pattern, "HANDLER", scopeRef, "MARIADB", physical,
                List.of(), List.of(), null, List.of(), "INFERRED", 0.9, null);
    }

    private static ConsistencyQueryService.ConsistencyScenarioView rulePackScenario(
            String id, String pattern, String scopeKind, String scopeRef,
            String participants, String invariants) {
        return new ConsistencyQueryService.ConsistencyScenarioView(
                id, pattern, scopeKind, scopeRef, "MARIADB", "Offer",
                List.of(),
                ConsistencyHintJsonParser.parseParticipants(participants),
                ConsistencyHintJsonParser.parsePollStrategy("{}"),
                ConsistencyHintJsonParser.parseInvariants(invariants),
                "RULE_PACK", 0.95, null);
    }
}
