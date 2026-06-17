package io.testseer.backend.query;

import io.testseer.backend.config.MessagingRulePack;
import io.testseer.backend.config.MessagingRulePackLoader;
import io.testseer.backend.config.WorkspaceCatalogService;
import io.testseer.backend.config.WorkspaceConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class MessagingFlowService {

    private final JdbcClient db;
    private final WorkspaceCatalogService workspaceCatalog;
    private final DataObjectMergeService mergeService;
    private final ConsistencyHintEnricher consistencyHintEnricher;
    private final EventFlowFirstHopEnricher firstHopEnricher;
    private final TerminalHopEnricher terminalHopEnricher;
    private final LiveConfigSnapshotService liveConfigSnapshotService;
    private final Optional<GcpPubSubVerifier> gcpVerifier;
    private final MessagingRulePackLoader messagingRulePackLoader;

    public MessagingFlowService(
            JdbcClient db,
            WorkspaceCatalogService workspaceCatalog,
            DataObjectMergeService mergeService,
            ConsistencyHintEnricher consistencyHintEnricher,
            EventFlowFirstHopEnricher firstHopEnricher,
            TerminalHopEnricher terminalHopEnricher,
            LiveConfigSnapshotService liveConfigSnapshotService,
            MessagingRulePackLoader messagingRulePackLoader,
            @Autowired(required = false) GcpPubSubVerifier gcpVerifier) {
        this.db = db;
        this.workspaceCatalog = workspaceCatalog;
        this.mergeService = mergeService;
        this.consistencyHintEnricher = consistencyHintEnricher;
        this.firstHopEnricher = firstHopEnricher;
        this.terminalHopEnricher = terminalHopEnricher;
        this.liveConfigSnapshotService = liveConfigSnapshotService;
        this.messagingRulePackLoader = messagingRulePackLoader;
        this.gcpVerifier = Optional.ofNullable(gcpVerifier);
    }

    public EventFlowReport traceTopicFlow(String serviceId, String shortId, String env) {
        return traceTopicFlow(serviceId, shortId, env, false);
    }

    public EventFlowReport traceTopicFlow(
            String serviceId, String shortId, String env, boolean includeExternal) {
        return traceTopicFlow(serviceId, shortId, env, includeExternal, false);
    }

    public EventFlowReport traceTopicFlow(
            String serviceId, String shortId, String env, boolean includeExternal, boolean liveVerify) {
        PubSubLiveInventoryResult<PubSubView> liveResult =
                applyLiveToPubSubViews(queryPubSub(serviceId, shortId, env), liveVerify);
        List<PubSubView> pubsub = liveResult.items();
        List<MessageSchemaView> schemas = querySchemas(serviceId, shortId);
        List<DataAccessView> dataAccess = queryDataAccess(serviceId);
        List<FlowGateView> gates = queryGatesWithLive(serviceId, env, false);
        List<ValidationHintView> hints = queryHints(serviceId, env);

        List<EventFlowStep> steps = buildSteps(pubsub, schemas, dataAccess, gates, hints);
        if (includeExternal) {
            steps = attachExternalEndpoints(steps, serviceId, env);
        }
        steps = consistencyHintEnricher.enrichSteps(serviceId, steps);
        steps = firstHopEnricher.enrichFirstStep(serviceId, steps);
        List<FlowGap> gaps = appendLiveVerificationGaps(
                pubsub, buildGaps(pubsub, schemas, gates), shortId);
        LivePubSubSummary summary = liveResult.summary();
        return new EventFlowReport(
                serviceId, shortId, env, steps, gaps, pubsub,
                summary.status(), summary.verifiedCount(), summary.skippedCount());
    }

    /**
     * Trace a topic across all indexed services in an org. The join key is
     * {@code short_id + env_lane} — the same PDN topic name in different repos
     * forms one logical hop (publisher service → topic → subscriber service).
     */
    public CrossRepoFlowReport traceCrossRepo(String orgId, String startTopic, String env, int maxHops) {
        return traceCrossRepo(orgId, startTopic, env, maxHops, null);
    }

    public CrossRepoFlowReport traceCrossRepo(
            String orgId, String startTopic, String env, int maxHops, String bundleName) {
        return traceCrossRepo(orgId, startTopic, env, maxHops, bundleName, false);
    }

    public CrossRepoFlowReport traceCrossRepo(
            String orgId, String startTopic, String env, int maxHops,
            String bundleName, boolean includeExternal) {
        return traceCrossRepo(orgId, startTopic, env, maxHops, bundleName, includeExternal, false);
    }

    public CrossRepoFlowReport traceCrossRepo(
            String orgId, String startTopic, String env, int maxHops,
            String bundleName, boolean includeExternal, boolean liveVerify) {
        return traceCrossRepo(orgId, startTopic, env, maxHops, bundleName, includeExternal, liveVerify,
                CrossRepoFollowPolicy.MODE_RUNTIME, false);
    }

    public CrossRepoFlowReport traceCrossRepo(
            String orgId, String startTopic, String env, int maxHops,
            String bundleName, boolean includeExternal, boolean liveVerify,
            String followMode, boolean includeManifest) {
        MessagingRulePack rulePack = messagingRulePackLoader.getRulePack();
        List<PubSubOrgView> all = queryPubSubForOrg(orgId, env);
        KafkaTopicAliasIndex topicAliases = KafkaTopicAliasIndex.from(rulePack);
        Map<String, List<PubSubOrgView>> byTopic = groupByCanonicalShortId(all, "TOPIC", topicAliases);
        List<String> missingForBundle = detectMissingBundleRepos(orgId, bundleName);

        CrossRepoFollowPolicy followPolicy = CrossRepoFollowPolicy.load(
                rulePack, workspaceCatalog, db, orgId, followMode, includeManifest);
        CrossRepoGapClassifier gapClassifier = new CrossRepoGapClassifier(
                rulePack.crossRepoTrace(), followPolicy.context().manifestOnlyRepos());

        List<CrossRepoHop> hops = new ArrayList<>();
        List<FlowGap> gaps = new ArrayList<>();
        java.util.Set<String> visitedTopics = new java.util.LinkedHashSet<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        queue.add(topicAliases.canonical(startTopic));
        int skippedExpansionTotal = 0;

        while (!queue.isEmpty() && hops.size() < maxHops) {
            String topic = queue.poll();
            if (!visitedTopics.add(topic)) continue;

            List<PubSubOrgView> publishers = byTopic.getOrDefault(topic, List.of()).stream()
                    .filter(p -> "PUBLISH".equals(p.role()))
                    .toList();
            List<PubSubOrgView> subscribers = findSubscribersForTopic(topic, all, topicAliases);

            int hopOrder = hops.size() + 1;
            if (publishers.isEmpty()) {
                gaps.add(publisherGap(topic, missingForBundle, hopOrder));
            }
            if (subscribers.isEmpty()) {
                gapClassifier.classifyMissingSubscriber(new CrossRepoGapClassifier.TopicContext(
                                topic, hopOrder, publishers, subscribers, missingForBundle))
                        .ifPresent(gaps::add);
            }

            List<PubSubOrgView> topicFacts = byTopic.getOrDefault(topic, List.of());
            String transport = resolveCrossRepoTransport(topicFacts, publishers, subscribers);
            hops.add(new CrossRepoHop(hopOrder, topic, transport, publishers, subscribers, List.of()));

            CrossRepoFollowPolicy.ExpansionResult expansion = followPolicy.expandFromSubscribers(
                    subscribers, all, topicAliases, visitedTopics);
            skippedExpansionTotal += expansion.skippedSubscribers();
            expansion.topics().forEach(queue::add);
        }

        hops = enrichCrossRepoHops(hops, env, orgId, bundleName);
        hops = firstHopEnricher.enrichFirstHopSubscribers(hops);
        TerminalHopEnricher.Result terminal = terminalHopEnricher.enrich(orgId, env, hops, gaps);
        hops = terminal.hops();
        gaps = new ArrayList<>(terminal.gaps());

        LivePubSubSummary liveSummary = LivePubSubSummary.disabled();
        if (shouldRunLivePubSubVerify(liveVerify)) {
            LivePubSubApplyResult liveResult = applyLivePubSubVerification(hops, gaps);
            hops = liveResult.hops();
            gaps = liveResult.gaps();
            liveSummary = liveResult.summary();
        }

        List<ConsistencyHintView> traceConsistencyHints = aggregateCrossRepoHints(hops);

        List<String> indexedServices = queryRegisteredServiceIds(orgId);
        List<ExternalEndpointView> externalEndpoints = includeExternal
                ? queryExternalEndpointsForOrg(orgId, env, null, null)
                : List.of();

        CrossRepoFlowPresenter.Result presented = CrossRepoFlowPresenter.present(
                startTopic, hops, gaps, followPolicy.context().effectiveFollowMode(),
                followPolicy.context().traceWarnings(), skippedExpansionTotal);
        return new CrossRepoFlowReport(
                orgId, startTopic, env, presented.hops(), presented.gaps(), traceConsistencyHints,
                indexedServices, missingForBundle, externalEndpoints,
                liveSummary.status(), liveSummary.verifiedCount(), liveSummary.skippedCount(),
                presented.hopSummaries(), presented.narrative(),
                followPolicy.context().effectiveFollowMode(),
                presented.traceWarnings(), presented.skippedExpansionCount());
    }

    static List<ConsistencyHintView> aggregateCrossRepoHints(List<CrossRepoHop> hops) {
        Map<String, ConsistencyHintView> byScenarioId = new LinkedHashMap<>();
        for (CrossRepoHop hop : hops) {
            for (PubSubOrgView node : hop.publishers()) {
                for (ConsistencyHintView hint : node.consistencyHints()) {
                    byScenarioId.putIfAbsent(hint.scenarioId(), hint);
                }
            }
            for (PubSubOrgView sub : hop.subscribers()) {
                for (ConsistencyHintView hint : sub.consistencyHints()) {
                    byScenarioId.putIfAbsent(hint.scenarioId(), hint);
                }
            }
        }
        return List.copyOf(byScenarioId.values());
    }

    private List<CrossRepoHop> enrichCrossRepoHops(
            List<CrossRepoHop> hops, String env, String orgId, String bundleName) {
        CrossRepoTraceContext traceContext = buildTraceContext(hops, env, orgId, bundleName);
        Map<String, List<DataAccessView>> dataAccessByService = traceContext.dataAccessByService();
        return consistencyHintEnricher.enrichCrossRepoHops(hops, (serviceId, handlerFqn) -> {
            List<DataAccessView> rows = dataAccessByService.getOrDefault(serviceId, List.of());
            return rows.stream()
                    .filter(row -> handlerFqn.equals(row.handlerClassFqn()))
                    .toList();
        }, traceContext);
    }

    CrossRepoTraceContext buildTraceContext(
            List<CrossRepoHop> hops, String env, String orgId, String bundleName) {
        java.util.Set<String> serviceIds = new java.util.LinkedHashSet<>();
        for (CrossRepoHop hop : hops) {
            hop.publishers().forEach(p -> serviceIds.add(p.serviceId()));
            hop.subscribers().forEach(s -> serviceIds.add(s.serviceId()));
        }
        Map<String, List<DataAccessView>> dataAccessByService = new LinkedHashMap<>();
        Map<String, List<FlowGateView>> gatesByService = new LinkedHashMap<>();
        for (String svcId : serviceIds) {
            dataAccessByService.put(svcId, queryDataAccess(svcId));
            gatesByService.put(svcId, queryGatesFromDb(svcId, env));
        }
        return new CrossRepoTraceContext(orgId, env, hops, serviceIds, dataAccessByService, gatesByService, bundleName);
    }

    /**
     * Runs live GCP subscription verification for all SUBSCRIBE hops when enabled.
     */
    private LivePubSubApplyResult applyLivePubSubVerification(
            List<CrossRepoHop> hops, List<FlowGap> gaps) {
        GcpPubSubVerifier verifier = gcpVerifier.orElseThrow();
        List<CrossRepoHop> enrichedHops = new ArrayList<>();
        List<FlowGap> enrichedGaps = new ArrayList<>(gaps);
        int verified = 0;
        int skipped = 0;
        int problems = 0;

        for (CrossRepoHop hop : hops) {
            List<PubSubOrgView> enrichedSubs = new ArrayList<>();
            for (PubSubOrgView sub : hop.subscribers()) {
                if (!"SUBSCRIPTION".equals(sub.resourceKind())) {
                    enrichedSubs.add(sub);
                    continue;
                }
                String fullResourceId = sub.fullResourceId();
                if (fullResourceId == null || fullResourceId.isBlank()) {
                    skipped++;
                    enrichedSubs.add(sub);
                    continue;
                }

                GcpPubSubVerifier.VerificationResult result = verifier.verifySubscription(
                        fullResourceId, sub.shortId(), hop.topicShortId(), true);
                LiveVerificationView liveView = toLiveVerificationView(result);
                enrichedSubs.add(sub.withLiveVerification(liveView));

                switch (result.status()) {
                    case OK -> verified++;
                    case SKIPPED -> skipped++;
                    case SUBSCRIPTION_MISSING -> {
                        problems++;
                        enrichedGaps.add(new FlowGap(
                                "GCP_SUBSCRIPTION_MISSING",
                                "GCP subscription not found: " + fullResourceId
                                        + " (service=" + sub.serviceId() + ", topic=" + hop.topicShortId() + ")",
                                hop.order(), hop.topicShortId()));
                    }
                    case TOPIC_MISMATCH -> {
                        problems++;
                        enrichedGaps.add(new FlowGap(
                                "GCP_TOPIC_MISMATCH",
                                "GCP subscription " + fullResourceId + " attached to "
                                        + result.liveTopicShortId() + ", expected "
                                        + result.expectedTopicShortId()
                                        + " (service=" + sub.serviceId() + ")",
                                hop.order(), hop.topicShortId()));
                    }
                    case ERROR -> skipped++;
                }
            }
            enrichedHops.add(new CrossRepoHop(
                    hop.order(), hop.topicShortId(), hop.transport(), hop.publishers(), enrichedSubs,
                    hop.terminalContinuations()));
        }

        String status = problems > 0 ? "PARTIAL" : "OK";
        return new LivePubSubApplyResult(
                enrichedHops, enrichedGaps,
                new LivePubSubSummary(status, verified, skipped));
    }

    private static LiveVerificationView toLiveVerificationView(GcpPubSubVerifier.VerificationResult result) {
        if (result.status() == GcpPubSubVerifier.Status.SKIPPED) {
            return null;
        }
        return new LiveVerificationView(
                result.status().name(),
                result.expectedTopicShortId(),
                result.liveTopicFullName(),
                result.liveTopicShortId(),
                result.verifiedAt(),
                result.evidenceSource());
    }

    private record LivePubSubApplyResult(
            List<CrossRepoHop> hops, List<FlowGap> gaps, LivePubSubSummary summary) {}

    private boolean shouldRunLivePubSubVerify(boolean liveVerify) {
        return gcpVerifier.isPresent()
                && gcpVerifier.get().shouldVerify(liveVerify)
                && gcpVerifier.get().canVerifyForRequest(liveVerify);
    }

    private List<FlowGap> appendLiveVerificationGaps(
            List<PubSubView> pubsub, List<FlowGap> gaps, String topicShortId) {
        List<FlowGap> enriched = new ArrayList<>(gaps);
        for (PubSubView row : pubsub) {
            LiveVerificationView live = row.liveVerification();
            if (live == null) {
                continue;
            }
            String hopTopic = topicShortId != null && !topicShortId.isBlank()
                    ? topicShortId
                    : row.shortId().replace("_S.", "_T.");
            switch (live.status()) {
                case "SUBSCRIPTION_MISSING" -> enriched.add(new FlowGap(
                        "GCP_SUBSCRIPTION_MISSING",
                        "GCP subscription not found: " + row.fullResourceId()
                                + " (shortId=" + row.shortId() + ", topic=" + hopTopic + ")"));
                case "TOPIC_MISMATCH" -> enriched.add(new FlowGap(
                        "GCP_TOPIC_MISMATCH",
                        "GCP subscription " + row.fullResourceId() + " attached to "
                                + live.liveTopicShortId() + ", expected "
                                + live.expectedTopicShortId()
                                + " (shortId=" + row.shortId() + ")"));
                default -> { }
            }
        }
        return enriched;
    }

    public record LivePubSubSummary(String status, int verifiedCount, int skippedCount) {
        public static LivePubSubSummary disabled() {
            return new LivePubSubSummary("DISABLED", 0, 0);
        }
    }

    private List<PubSubOrgView> findSubscribersForTopic(
            String topic, List<PubSubOrgView> all, KafkaTopicAliasIndex topicAliases) {
        String topicStem = topic.contains("_T.") ? topic.substring(topic.indexOf("_T.") + 3) : topic;
        return all.stream()
                .filter(p -> "SUBSCRIBE".equals(p.role()))
                .filter(p -> topicAliases.equivalent(p.shortId(), topic)
                        || p.shortId().equals(topic)
                        || p.shortId().contains(topicStem)
                        || p.shortId().replace("_S.", "_T.").equals(topic))
                .toList();
    }

    private static FlowGap publisherGap(String topic, List<String> missingForBundle, int hopOrder) {
        String code = missingForBundle.isEmpty() ? "NO_PUBLISHER" : "NO_PUBLISHER_INDEX_GAP";
        String detail = missingForBundle.isEmpty()
                ? "No publisher indexed for topic " + topic
                : "No publisher indexed for topic " + topic + "; bundle repos not indexed: " + missingForBundle;
        return new FlowGap(code, detail, hopOrder, topic);
    }

    private static String resolveCrossRepoTransport(
            List<PubSubOrgView> topicFacts,
            List<PubSubOrgView> publishers,
            List<PubSubOrgView> subscribers) {
        String transport = MessagingTransportUtil.resolveHopTransport(topicFacts);
        if (transport == null || "PUBSUB".equals(transport)) {
            List<PubSubOrgView> hopParticipants = new ArrayList<>();
            hopParticipants.addAll(publishers);
            hopParticipants.addAll(subscribers);
            hopParticipants.addAll(topicFacts);
            String resolved = MessagingTransportUtil.resolveHopTransport(hopParticipants);
            if (resolved != null && !resolved.isBlank() && !"PUBSUB".equals(resolved)) {
                transport = resolved;
            } else if (hopParticipants.stream().allMatch(p ->
                    p.transport() != null && "KAFKA".equalsIgnoreCase(p.transport()))) {
                transport = "KAFKA";
            }
        }
        return transport;
    }

    private Map<String, List<PubSubOrgView>> groupByCanonicalShortId(
            List<PubSubOrgView> all, String kind, KafkaTopicAliasIndex topicAliases) {
        Map<String, List<PubSubOrgView>> map = new LinkedHashMap<>();
        for (PubSubOrgView p : all) {
            if (!kind.equals(p.resourceKind())) continue;
            String key = topicAliases.canonical(p.shortId());
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }
        return map;
    }

    private List<String> queryRegisteredServiceIds(String orgId) {
        return db.sql("SELECT service_id FROM service_registry WHERE org_id = :orgId ORDER BY service_id")
                .param("orgId", orgId)
                .query(String.class)
                .list();
    }

    private List<String> detectMissingBundleRepos(String orgId, String bundleName) {
        WorkspaceConfig workspaceConfig = workspaceCatalog.config(orgId);
        String resolvedBundle = workspaceConfig.resolveBundleName(bundleName);
        WorkspaceConfig.BundleConfig bundle = workspaceConfig.bundles() != null
                ? workspaceConfig.bundles().get(resolvedBundle) : null;
        if (bundle == null) {
            return List.of();
        }

        java.util.Set<String> registeredKeys = new java.util.HashSet<>();
        db.sql("SELECT repo, service_name FROM service_registry WHERE org_id = :orgId")
                .param("orgId", orgId)
                .query((rs, row) -> {
                    registeredKeys.add(rs.getString("repo"));
                    registeredKeys.add(rs.getString("service_name"));
                    return null;
                })
                .list();

        if (bundle.indexOrder() != null && !bundle.indexOrder().isEmpty()) {
            List<String> missing = new ArrayList<>();
            for (WorkspaceConfig.BundleIndexEntry entry : bundle.indexOrder()) {
                String targetId = firstNonBlank(entry.repo(), entry.serviceModule(), entry.catalogLibrary());
                if (targetId == null || targetId.isBlank()) {
                    continue;
                }
                if (!isBundleTargetRegistered(workspaceConfig, entry, registeredKeys)) {
                    missing.add(targetId);
                }
            }
            return missing;
        }

        List<String> expected = bundle.repos() != null ? bundle.repos() : List.of();
        return expected.stream().filter(r -> !registeredKeys.contains(r)).toList();
    }

    private static boolean isBundleTargetRegistered(
            WorkspaceConfig config,
            WorkspaceConfig.BundleIndexEntry entry,
            java.util.Set<String> registeredKeys) {
        if (entry.repo() != null && registeredKeys.contains(entry.repo())) {
            return true;
        }
        if (entry.serviceModule() != null) {
            if (registeredKeys.contains(entry.serviceModule())) {
                return true;
            }
            return config.serviceModule(entry.serviceModule())
                    .map(m -> registeredKeys.contains(m.repo()))
                    .orElse(false);
        }
        if (entry.catalogLibrary() != null) {
            if (registeredKeys.contains(entry.catalogLibrary())) {
                return true;
            }
            return config.catalogLibrary(entry.catalogLibrary())
                    .map(c -> registeredKeys.contains(c.repo()) || registeredKeys.contains(c.serviceName()))
                    .orElse(false);
        }
        return false;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private Map<String, List<PubSubOrgView>> groupByShortId(List<PubSubOrgView> all, String kind) {
        Map<String, List<PubSubOrgView>> map = new LinkedHashMap<>();
        for (PubSubOrgView p : all) {
            if (!kind.equals(p.resourceKind())) continue;
            map.computeIfAbsent(p.shortId(), k -> new ArrayList<>()).add(p);
        }
        return map;
    }

    /**
     * Org-wide pub/sub inventory for cross-repo tracing.
     * {@code env} is echoed on reports only; lane filtering is disabled until per-env
     * commit resolution can drive flow selection (BL-050 follow-on).
     */
    public List<PubSubOrgView> queryPubSubForOrg(String orgId, String env) {
        return db.sql("""
                SELECT p.service_id, s.repo, s.service_name, p.resource_kind, p.short_id, p.env_lane,
                       p.role, p.spring_key, p.full_resource_id, p.module_name, p.linked_class_fqn,
                       p.workload_name, p.evidence_source, p.confidence, p.attributes
                FROM pubsub_resource_facts p
                JOIN service_registry s ON p.service_id = s.service_id
                WHERE p.org_id = :orgId
                ORDER BY p.env_lane NULLS FIRST, p.short_id, p.role
                """)
                .param("orgId", orgId)
                .query((rs, row) -> new PubSubOrgView(
                rs.getString("service_id"), rs.getString("repo"), rs.getString("service_name"),
                rs.getString("resource_kind"), rs.getString("short_id"), rs.getString("env_lane"),
                rs.getString("role"), rs.getString("spring_key"), rs.getString("full_resource_id"),
                rs.getString("module_name"), rs.getString("linked_class_fqn"),
                rs.getString("workload_name"), rs.getString("evidence_source"), rs.getDouble("confidence"),
                MessagingTransportUtil.fromAttributes(rs.getString("attributes")),
                List.of(), List.of(), null
        )).list();
    }

    private static String effectiveTransport(PubSubView pubsub) {
        if (pubsub.transport() != null && !pubsub.transport().isBlank()) {
            return pubsub.transport();
        }
        return "PUBLISH".equals(pubsub.role()) ? "PUBSUB" : null;
    }

    private static List<OutboundMsg> appendOutbound(List<OutboundMsg> existing, OutboundMsg outbound) {
        if (outbound == null) {
            return existing != null ? existing : List.of();
        }
        List<OutboundMsg> next = new ArrayList<>(existing != null ? existing : List.of());
        boolean duplicate = next.stream().anyMatch(o ->
                o.topicOrType().equals(outbound.topicOrType()) && o.role().equals(outbound.role()));
        if (!duplicate) {
            next.add(outbound);
        }
        return List.copyOf(next);
    }

    private static EventFlowStep withOutbounds(EventFlowStep step, List<OutboundMsg> outbounds) {
        return new EventFlowStep(
                step.order(), step.workload(), step.handler(),
                step.reads(), step.writes(), outbounds, step.inbound(),
                step.gates(), step.validationHints(), step.externalEndpoints(),
                step.consistencyHints(), step.inboundTriggers());
    }

    private List<EventFlowStep> buildSteps(
            List<PubSubView> pubsub,
            List<MessageSchemaView> schemas,
            List<DataAccessView> dataAccess,
            List<FlowGateView> gates,
            List<ValidationHintView> hints) {

        Map<String, EventFlowStep> byClass = new LinkedHashMap<>();
        int[] order = {1};

        for (PubSubView p : pubsub) {
            String key = p.linkedClassFqn() != null ? p.linkedClassFqn() : p.moduleName();
            EventFlowStep step = byClass.computeIfAbsent(key, k -> new EventFlowStep(
                    order[0]++, p.workloadName(), p.linkedClassFqn(), List.of(), List.of(),
                    List.of(), null, List.of(), List.of(), List.of(), List.of(), List.of()
            ));
            if ("PUBLISH".equals(p.role())) {
                OutboundMsg outbound = new OutboundMsg(
                        p.shortId(), p.role(), p.fullResourceId(), effectiveTransport(p));
                byClass.put(key, withOutbounds(step, appendOutbound(step.outbounds(), outbound)));
            } else {
                byClass.put(key, new EventFlowStep(
                        step.order(), step.workload(), step.handler(),
                        step.reads(), step.writes(),
                        step.outbounds(),
                        new InboundMsg(p.shortId(), p.role(), p.fullResourceId()),
                        step.gates(), step.validationHints(), step.externalEndpoints(),
                        step.consistencyHints(), step.inboundTriggers()
                ));
            }
        }

        for (MessageSchemaView s : schemas) {
            if (s.linkedClassFqn() == null) continue;
            EventFlowStep step = byClass.get(s.linkedClassFqn());
            if (step == null) continue;
            InboundMsg inbound = step.inbound();
            if (inbound == null && "INBOUND".equals(s.direction())) {
                inbound = new InboundMsg(s.topicShortId(), s.direction(), s.payloadProto());
            }
            List<OutboundMsg> outbounds = new ArrayList<>(step.outbounds());
            if (outbounds.isEmpty() && "OUTBOUND".equals(s.direction())) {
                outbounds = appendOutbound(outbounds,
                        new OutboundMsg(s.topicShortId(), s.direction(), s.payloadProto(), null));
            }
            byClass.put(s.linkedClassFqn(), new EventFlowStep(
                    step.order(), step.workload(), step.handler(),
                    step.reads(), step.writes(), outbounds, inbound, step.gates(), step.validationHints(),
                    step.externalEndpoints(), step.consistencyHints(), step.inboundTriggers()
            ));
        }

        for (DataAccessView d : dataAccess) {
            EventFlowStep step = byClass.get(d.handlerClassFqn());
            if (step == null) continue;
            List<DataAccessView> reads = new ArrayList<>(step.reads());
            List<DataAccessView> writes = new ArrayList<>(step.writes());
            if ("READ".equals(d.operation())) reads.add(d);
            else writes.add(d);
            byClass.put(d.handlerClassFqn(), new EventFlowStep(
                    step.order(), step.workload(), step.handler(),
                    reads, writes, step.outbounds(), step.inbound(), step.gates(), step.validationHints(),
                    step.externalEndpoints(), step.consistencyHints(), step.inboundTriggers()
            ));
        }

        for (FlowGateView g : gates) {
            EventFlowStep step = byClass.get(g.guardedSymbolFqn());
            if (step == null) continue;
            List<FlowGateView> gateList = new ArrayList<>(step.gates());
            gateList.add(g);
            byClass.put(g.guardedSymbolFqn(), new EventFlowStep(
                    step.order(), step.workload(), step.handler(),
                    step.reads(), step.writes(), step.outbounds(), step.inbound(),
                    gateList, step.validationHints(), step.externalEndpoints(), step.consistencyHints(),
                    step.inboundTriggers()
            ));
        }

        for (ValidationHintView h : hints) {
            for (Map.Entry<String, EventFlowStep> e : byClass.entrySet()) {
                if (h.linkedSymbolFqn() != null && !h.linkedSymbolFqn().equals(e.getValue().handler())) {
                    continue;
                }
                EventFlowStep step = e.getValue();
                List<ValidationHintView> hintList = new ArrayList<>(step.validationHints());
                hintList.add(h);
                byClass.put(e.getKey(), new EventFlowStep(
                        step.order(), step.workload(), step.handler(),
                        step.reads(), step.writes(), step.outbounds(), step.inbound(),
                        step.gates(), hintList, step.externalEndpoints(), step.consistencyHints(),
                        step.inboundTriggers()
                ));
            }
        }

        return byClass.values().stream()
                .sorted(Comparator.comparingInt(EventFlowStep::order))
                .toList();
    }

    private List<FlowGap> buildGaps(
            List<PubSubView> pubsub, List<MessageSchemaView> schemas, List<FlowGateView> gates) {
        List<FlowGap> gaps = new ArrayList<>();
        for (PubSubView p : pubsub) {
            if (p.linkedClassFqn() == null) {
                if ("PUBLISH".equals(p.role()) && "KAFKA".equals(effectiveTransport(p))) {
                    gaps.add(new FlowGap(
                            "UNLINKED_KAFKA_PUBLISHER",
                            p.shortId() + " has no linked Kafka producer class"));
                } else {
                    gaps.add(new FlowGap("UNLINKED_PUBSUB", p.shortId() + " has no linked Java class"));
                }
            }
        }
        if (schemas.isEmpty() && !pubsub.isEmpty()) {
            gaps.add(new FlowGap("MISSING_SCHEMA", "No message schema facts linked to topic flow"));
        }
        if (gates.isEmpty() && !pubsub.isEmpty()) {
            gaps.add(new FlowGap("UNGUARDED_STEP", "No flow gates indexed for pub/sub handlers"));
        }
        return gaps;
    }

    /**
     * Service-scoped pub/sub facts. {@code env} is accepted for API compatibility but does not
     * filter rows until per-env index commit resolution is implemented.
     */
    public List<PubSubView> queryPubSub(String serviceId, String shortId, String env) {
        StringBuilder sql = new StringBuilder("""
                SELECT resource_kind, short_id, env_lane, role, spring_key, full_resource_id,
                       module_name, linked_class_fqn, workload_name, evidence_source, confidence,
                       attributes
                FROM pubsub_resource_facts
                WHERE service_id = :svcId
                """);
        if (shortId != null && !shortId.isBlank()) {
            sql.append(" AND short_id = :shortId");
        }
        sql.append(" ORDER BY env_lane NULLS FIRST, short_id, role");

        var spec = db.sql(sql.toString()).param("svcId", serviceId);
        if (shortId != null && !shortId.isBlank()) spec = spec.param("shortId", shortId);

        return spec.query((rs, row) -> new PubSubView(
                rs.getString("resource_kind"), rs.getString("short_id"), rs.getString("env_lane"),
                rs.getString("role"), rs.getString("spring_key"), rs.getString("full_resource_id"),
                rs.getString("module_name"), rs.getString("linked_class_fqn"),
                rs.getString("workload_name"), rs.getString("evidence_source"), rs.getDouble("confidence"),
                MessagingTransportUtil.fromAttributes(rs.getString("attributes")),
                null
        )).list();
    }

    public PubSubLiveInventoryResult<PubSubView> queryPubSubWithLive(
            String serviceId, String shortId, String env, boolean liveVerify) {
        return applyLiveToPubSubViews(queryPubSub(serviceId, shortId, env), liveVerify);
    }

    public PubSubLiveInventoryResult<PubSubOrgView> queryPubSubForOrgWithLive(
            String orgId, String env, boolean liveVerify) {
        return applyLiveToPubSubOrgViews(queryPubSubForOrg(orgId, env), liveVerify);
    }

    private PubSubLiveInventoryResult<PubSubView> applyLiveToPubSubViews(
            List<PubSubView> rows, boolean liveVerify) {
        if (!shouldRunLivePubSubVerify(liveVerify)) {
            return new PubSubLiveInventoryResult<>(rows, LivePubSubSummary.disabled());
        }
        GcpPubSubVerifier verifier = gcpVerifier.get();
        List<PubSubView> enriched = new ArrayList<>();
        int verified = 0;
        int skipped = 0;
        int problems = 0;
        for (PubSubView row : rows) {
            if (!isSubscriptionRow(row.resourceKind(), row.role())) {
                enriched.add(row);
                continue;
            }
            LiveVerifyCounts counts = verifyInventoryRow(
                    verifier, row.fullResourceId(), row.shortId(), null, liveVerify);
            enriched.add(row.withLiveVerification(counts.liveView()));
            verified += counts.verified();
            skipped += counts.skipped();
            problems += counts.problems();
        }
        String status = problems > 0 ? "PARTIAL" : "OK";
        return new PubSubLiveInventoryResult<>(enriched, new LivePubSubSummary(status, verified, skipped));
    }

    private PubSubLiveInventoryResult<PubSubOrgView> applyLiveToPubSubOrgViews(
            List<PubSubOrgView> rows, boolean liveVerify) {
        if (!shouldRunLivePubSubVerify(liveVerify)) {
            return new PubSubLiveInventoryResult<>(rows, LivePubSubSummary.disabled());
        }
        GcpPubSubVerifier verifier = gcpVerifier.get();
        List<PubSubOrgView> enriched = new ArrayList<>();
        int verified = 0;
        int skipped = 0;
        int problems = 0;
        for (PubSubOrgView row : rows) {
            if (!isSubscriptionRow(row.resourceKind(), row.role())) {
                enriched.add(row);
                continue;
            }
            String hopTopic = verifier.resolveExpectedTopic(row.shortId(), null);
            LiveVerifyCounts counts = verifyInventoryRow(
                    verifier, row.fullResourceId(), row.shortId(), hopTopic, liveVerify);
            enriched.add(row.withLiveVerification(counts.liveView()));
            verified += counts.verified();
            skipped += counts.skipped();
            problems += counts.problems();
        }
        String status = problems > 0 ? "PARTIAL" : "OK";
        return new PubSubLiveInventoryResult<>(enriched, new LivePubSubSummary(status, verified, skipped));
    }

    private static boolean isSubscriptionRow(String resourceKind, String role) {
        return "SUBSCRIPTION".equals(resourceKind) || "SUBSCRIBE".equals(role);
    }

    private record LiveVerifyCounts(LiveVerificationView liveView, int verified, int skipped, int problems) {}

    private LiveVerifyCounts verifyInventoryRow(
            GcpPubSubVerifier verifier,
            String fullResourceId,
            String shortId,
            String hopTopicShortId,
            boolean liveVerify) {
        if (fullResourceId == null || fullResourceId.isBlank()) {
            return new LiveVerifyCounts(null, 0, 1, 0);
        }
        GcpPubSubVerifier.VerificationResult result = verifier.verifySubscription(
                fullResourceId, shortId, hopTopicShortId, liveVerify);
        LiveVerificationView liveView = toLiveVerificationView(result);
        return switch (result.status()) {
            case OK -> new LiveVerifyCounts(liveView, 1, 0, 0);
            case SKIPPED -> new LiveVerifyCounts(liveView, 0, 1, 0);
            case ERROR -> new LiveVerifyCounts(liveView, 0, 1, 0);
            case SUBSCRIPTION_MISSING, TOPIC_MISMATCH -> new LiveVerifyCounts(liveView, 0, 0, 1);
        };
    }

    public List<MessageSchemaView> querySchemas(String serviceId, String topicShortId) {
        var spec = db.sql("""
                SELECT payload_proto, direction, topic_short_id, linked_class_fqn, payload_fields,
                       unpack_expression, evidence_source, confidence
                FROM message_schema_facts
                WHERE service_id = :svcId
                """)
                .param("svcId", serviceId);
        if (topicShortId != null && !topicShortId.isBlank()) {
            spec = db.sql("""
                    SELECT payload_proto, direction, topic_short_id, linked_class_fqn, payload_fields,
                           unpack_expression, evidence_source, confidence
                    FROM message_schema_facts
                    WHERE service_id = :svcId AND (topic_short_id = :topic OR topic_short_id IS NULL)
                    """).param("svcId", serviceId).param("topic", topicShortId);
        }
        return spec.query((rs, row) -> new MessageSchemaView(
                rs.getString("payload_proto"), rs.getString("direction"), rs.getString("topic_short_id"),
                rs.getString("linked_class_fqn"), rs.getString("payload_fields"),
                rs.getString("unpack_expression"), rs.getString("evidence_source"), rs.getDouble("confidence")
        )).list();
    }

    public List<DataAccessView> queryDataAccess(String serviceId) {
        return queryDataAccess(serviceId, null, true);
    }

    public List<DataAccessView> queryDataAccess(
            String serviceId, String packagePrefix, boolean excludeTestHandlers) {
        String orgId = db.sql("SELECT org_id FROM service_registry WHERE service_id = :svcId LIMIT 1")
                .param("svcId", serviceId)
                .query(String.class)
                .optional()
                .orElse(null);

        List<DataAccessView> raw = db.sql("""
                SELECT handler_class_fqn, handler_method, operation, table_or_entity, dao_method,
                       correlation_keys, evidence_source, confidence,
                       store_type, entity_fqn, domain_fqn, accessor_fqn, accessor_kind, catalog_ref,
                       secondary_stores
                FROM data_access_facts
                WHERE service_id = :svcId
                ORDER BY handler_class_fqn, operation
                """)
                .param("svcId", serviceId)
                .query((rs, row) -> new DataAccessView(
                        rs.getString("handler_class_fqn"), rs.getString("handler_method"),
                        rs.getString("operation"), rs.getString("table_or_entity"),
                        rs.getString("dao_method"), rs.getString("correlation_keys"),
                        rs.getString("evidence_source"), rs.getDouble("confidence"),
                        rs.getString("store_type"), rs.getString("entity_fqn"),
                        rs.getString("domain_fqn"), rs.getString("accessor_fqn"),
                        rs.getString("accessor_kind"), rs.getString("catalog_ref"),
                        rs.getString("secondary_stores"),
                        rs.getString("table_or_entity"), null, null, null,
                        List.of()
                )).list();

        if (orgId == null) return filterDataAccess(raw, packagePrefix, excludeTestHandlers);
        return filterDataAccess(
                consistencyHintEnricher.enrichDataAccess(serviceId, mergeService.mergeAll(orgId, raw)),
                packagePrefix,
                excludeTestHandlers);
    }

    private static List<DataAccessView> filterDataAccess(
            List<DataAccessView> rows, String packagePrefix, boolean excludeTestHandlers) {
        return rows.stream()
                .filter(row -> !excludeTestHandlers || HandlerScopeFilter.isProductionHandler(row.handlerClassFqn()))
                .filter(row -> packagePrefix == null || packagePrefix.isBlank()
                        || PackagePrefixFilter.matches(row.handlerClassFqn(), packagePrefix))
                .toList();
    }

    public List<FlowGateView> queryGates(String serviceId, String env) {
        return queryGatesWithLive(serviceId, env, false);
    }

    public LiveConfigSnapshotService.OverlayContext queryGatesOverlayContext(String env) {
        return liveConfigSnapshotService.overlayContext(env);
    }

    public List<FlowGateView> queryGatesWithLive(String serviceId, String env, boolean refreshLive) {
        return queryGatesWithLive(serviceId, env, refreshLive, null);
    }

    public List<FlowGateView> queryGatesWithLive(
            String serviceId, String env, boolean refreshLive, String packagePrefix) {
        List<FlowGateView> gates = queryGatesFromDb(serviceId, env, packagePrefix);
        return liveConfigSnapshotService.overlayGates(env, gates, refreshLive);
    }

    private List<FlowGateView> queryGatesFromDb(String serviceId, String env) {
        return queryGatesFromDb(serviceId, env, null);
    }

    private List<FlowGateView> queryGatesFromDb(String serviceId, String env, String packagePrefix) {
        String prefixClause = "";
        if (packagePrefix != null && !packagePrefix.isBlank()) {
            prefixClause = """
                    AND (
                      f.guarded_symbol_fqn LIKE :prefix || '%'
                      OR EXISTS (
                        SELECT 1 FROM graph_edges e
                        JOIN graph_nodes gn_from ON gn_from.id = e.from_node
                        JOIN graph_nodes gn_to ON gn_to.id = e.to_node
                        WHERE e.edge_type IN ('DEPENDS_ON', 'INVOKES')
                          AND gn_from.symbol_fqn LIKE :prefix || '%'
                          AND gn_to.symbol_fqn = split_part(f.guarded_symbol_fqn, '#', 1)
                      )
                    )
                    """;
        }
        var spec = db.sql("""
                SELECT f.guarded_symbol_fqn, f.guarded_flow_step, f.gate_kind, f.gate_key, f.required_value,
                       f.effect_when_fail, f.test_precondition, f.evidence_source, f.confidence
                FROM flow_gate_facts f
                WHERE f.service_id = :svcId
                """ + prefixClause).param("svcId", serviceId);
        if (packagePrefix != null && !packagePrefix.isBlank()) {
            spec = spec.param("prefix", packagePrefix);
        }
        if (env != null && !env.isBlank()) {
            spec = db.sql("""
                    SELECT f.guarded_symbol_fqn, f.guarded_flow_step, f.gate_kind, f.gate_key, f.required_value,
                           f.effect_when_fail, f.test_precondition, f.evidence_source, f.confidence
                    FROM flow_gate_facts f
                    WHERE f.service_id = :svcId AND (f.env_lane = :env OR f.env_lane = 'unknown')
                    """ + prefixClause)
                    .param("svcId", serviceId)
                    .param("env", env);
            if (packagePrefix != null && !packagePrefix.isBlank()) {
                spec = spec.param("prefix", packagePrefix);
            }
        }
        return spec.query((rs, row) -> FlowGateView.fromDb(
                rs.getString("guarded_symbol_fqn"), rs.getString("guarded_flow_step"),
                rs.getString("gate_kind"), rs.getString("gate_key"), rs.getString("required_value"),
                rs.getString("effect_when_fail"), rs.getString("test_precondition"),
                rs.getString("evidence_source"), rs.getDouble("confidence")
        )).list();
    }

    public List<ValidationHintView> queryHints(String serviceId, String env) {
        var spec = db.sql("""
                SELECT flow_step, hint_kind, hint_value, linked_symbol_fqn, env_lane
                FROM validation_hint_facts
                WHERE service_id = :svcId
                """).param("svcId", serviceId);
        if (env != null && !env.isBlank()) {
            spec = db.sql("""
                    SELECT flow_step, hint_kind, hint_value, linked_symbol_fqn, env_lane
                    FROM validation_hint_facts
                    WHERE service_id = :svcId AND (env_lane = :env OR env_lane = 'unknown')
                    """).param("svcId", serviceId).param("env", env);
        }
        return spec.query((rs, row) -> new ValidationHintView(
                rs.getString("flow_step"), rs.getString("hint_kind"), rs.getString("hint_value"),
                rs.getString("linked_symbol_fqn"), rs.getString("env_lane")
        )).list();
    }

    public List<ExternalEndpointView> queryExternalEndpoints(
            String serviceId, String env, String partner, String flowStep) {
        StringBuilder sql = new StringBuilder("""
                SELECT endpoint_id, partner_slug, operation, http_method, url_template, url_resolved,
                       env_lane, boundary, config_key, caller_class_fqn, client_class_fqn, flow_step,
                       auth_scheme, evidence_source, confidence
                FROM external_endpoint_facts
                WHERE service_id = :svcId
                """);
        if (env != null && !env.isBlank()) {
            sql.append(" AND env_lane = :env");
        }
        if (partner != null && !partner.isBlank()) {
            sql.append(" AND partner_slug = :partner");
        }
        if (flowStep != null && !flowStep.isBlank()) {
            sql.append(" AND flow_step = :flowStep");
        }
        sql.append(" ORDER BY endpoint_id, env_lane");

        var spec = db.sql(sql.toString()).param("svcId", serviceId);
        if (env != null && !env.isBlank()) spec = spec.param("env", env);
        if (partner != null && !partner.isBlank()) spec = spec.param("partner", partner);
        if (flowStep != null && !flowStep.isBlank()) spec = spec.param("flowStep", flowStep);

        return spec.query((rs, row) -> mapExternalEndpoint(rs)).list();
    }

    public List<ExternalEndpointView> queryExternalEndpointsForOrg(
            String orgId, String env, String partner, String flowStep) {
        StringBuilder sql = new StringBuilder("""
                SELECT e.endpoint_id, e.partner_slug, e.operation, e.http_method, e.url_template,
                       e.url_resolved, e.env_lane, e.boundary, e.config_key, e.caller_class_fqn,
                       e.client_class_fqn, e.flow_step, e.auth_scheme, e.evidence_source, e.confidence,
                       e.service_id, s.service_name
                FROM external_endpoint_facts e
                JOIN service_registry s ON e.service_id = s.service_id
                WHERE e.org_id = :orgId
                """);
        if (env != null && !env.isBlank()) sql.append(" AND e.env_lane = :env");
        if (partner != null && !partner.isBlank()) sql.append(" AND e.partner_slug = :partner");
        if (flowStep != null && !flowStep.isBlank()) sql.append(" AND e.flow_step = :flowStep");
        sql.append(" ORDER BY e.service_id, e.endpoint_id");

        var spec = db.sql(sql.toString()).param("orgId", orgId);
        if (env != null && !env.isBlank()) spec = spec.param("env", env);
        if (partner != null && !partner.isBlank()) spec = spec.param("partner", partner);
        if (flowStep != null && !flowStep.isBlank()) spec = spec.param("flowStep", flowStep);

        return spec.query((rs, row) -> new ExternalEndpointView(
                rs.getString("endpoint_id"),
                rs.getString("partner_slug"),
                rs.getString("operation"),
                rs.getString("http_method"),
                rs.getString("url_template"),
                rs.getString("url_resolved"),
                rs.getString("env_lane"),
                rs.getString("boundary"),
                rs.getString("config_key"),
                rs.getString("caller_class_fqn"),
                rs.getString("client_class_fqn"),
                rs.getString("flow_step"),
                rs.getString("auth_scheme"),
                rs.getString("evidence_source"),
                rs.getDouble("confidence"),
                rs.getString("service_id"),
                rs.getString("service_name")
        )).list();
    }

    private List<EventFlowStep> attachExternalEndpoints(
            List<EventFlowStep> steps, String serviceId, String env) {
        List<ExternalEndpointView> all = queryExternalEndpoints(serviceId, env, null, null);
        if (all.isEmpty()) return steps;

        List<EventFlowStep> enriched = new ArrayList<>();
        for (EventFlowStep step : steps) {
            List<ExternalEndpointView> matched = all.stream()
                    .filter(ep -> step.handler() != null && (
                            step.handler().equals(ep.callerClassFqn())
                                    || (ep.callerClassFqn() != null
                                    && step.handler().contains(simpleName(ep.callerClassFqn())))
                                    || (ep.flowStep() != null && step.gates().stream()
                                    .anyMatch(g -> ep.flowStep().equals(g.guardedFlowStep())))))
                    .toList();
            enriched.add(new EventFlowStep(
                    step.order(), step.workload(), step.handler(),
                    step.reads(), step.writes(), step.outbounds(), step.inbound(),
                    step.gates(), step.validationHints(), matched, step.consistencyHints(),
                    step.inboundTriggers()
            ));
        }
        return enriched;
    }

    private static ExternalEndpointView mapExternalEndpoint(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ExternalEndpointView(
                rs.getString("endpoint_id"),
                rs.getString("partner_slug"),
                rs.getString("operation"),
                rs.getString("http_method"),
                rs.getString("url_template"),
                rs.getString("url_resolved"),
                rs.getString("env_lane"),
                rs.getString("boundary"),
                rs.getString("config_key"),
                rs.getString("caller_class_fqn"),
                rs.getString("client_class_fqn"),
                rs.getString("flow_step"),
                rs.getString("auth_scheme"),
                rs.getString("evidence_source"),
                rs.getDouble("confidence"),
                null,
                null
        );
    }

    private static String simpleName(String fqn) {
        if (fqn == null) return "";
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    public record EventFlowReport(
            String serviceId, String topicShortId, String envLane,
            List<EventFlowStep> steps, List<FlowGap> gaps,
            List<PubSubView> pubsubResources,
            String livePubSubStatus,
            Integer livePubSubVerifiedCount,
            Integer livePubSubSkippedCount
    ) {
        public static EventFlowReport withoutLive(
                String serviceId, String topicShortId, String envLane,
                List<EventFlowStep> steps, List<FlowGap> gaps) {
            return new EventFlowReport(
                    serviceId, topicShortId, envLane, steps, gaps,
                    List.of(), "DISABLED", 0, 0);
        }
    }

    public record EventFlowStep(
            int order, String workload, String handler,
            List<DataAccessView> reads, List<DataAccessView> writes,
            List<OutboundMsg> outbounds, InboundMsg inbound,
            List<FlowGateView> gates, List<ValidationHintView> validationHints,
            List<ExternalEndpointView> externalEndpoints,
            List<ConsistencyHintView> consistencyHints,
            List<EntryFlowService.EntryTriggerView> inboundTriggers
    ) {
        /** Backward-compatible accessor: first outbound or null. */
        public OutboundMsg outbound() {
            return outbounds == null || outbounds.isEmpty() ? null : outbounds.get(0);
        }

        public EventFlowStep withInboundTriggers(List<EntryFlowService.EntryTriggerView> triggers) {
            return new EventFlowStep(
                    order, workload, handler, reads, writes, outbounds, inbound,
                    gates, validationHints, externalEndpoints, consistencyHints,
                    triggers != null ? triggers : List.of());
        }
    }

    public record InboundMsg(String topicOrType, String role, String payloadOrResource) {}

    public record OutboundMsg(String topicOrType, String role, String payloadOrResource, String transport) {
        public OutboundMsg(String topicOrType, String role, String payloadOrResource) {
            this(topicOrType, role, payloadOrResource, null);
        }
    }
    public record FlowGap(String gapType, String description, Integer hopOrder, String topicShortId) {
        public FlowGap(String gapType, String description) {
            this(gapType, description, null, null);
        }
    }

    public record HopParticipantSummary(
            String serviceName,
            String repo,
            String serviceId,
            String linkedClassSimpleName,
            String role,
            String transport,
            String evidenceSource,
            String label) {}

    public record CrossRepoHopSummary(
            int order,
            String topicShortId,
            String transport,
            List<HopParticipantSummary> publishers,
            List<HopParticipantSummary> subscribers,
            String summaryLine) {}

    public record PubSubView(
            String resourceKind, String shortId, String envLane, String role,
            String springKey, String fullResourceId, String moduleName,
            String linkedClassFqn, String workloadName, String evidenceSource, double confidence,
            String transport,
            LiveVerificationView liveVerification
    ) {
        public PubSubView withLiveVerification(LiveVerificationView live) {
            return new PubSubView(
                    resourceKind, shortId, envLane, role, springKey, fullResourceId,
                    moduleName, linkedClassFqn, workloadName, evidenceSource, confidence, transport, live);
        }
    }

    public record PubSubLiveInventoryResult<T>(List<T> items, LivePubSubSummary summary) {}

    public record MessageSchemaView(
            String payloadProto, String direction, String topicShortId,
            String linkedClassFqn, String payloadFields, String unpackExpression,
            String evidenceSource, double confidence
    ) {}

    public record DataAccessView(
            String handlerClassFqn, String handlerMethod, String operation,
            String tableOrEntity, String daoMethod, String correlationKeys,
            String evidenceSource, double confidence,
            String storeType, String entityFqn, String domainFqn,
            String accessorFqn, String accessorKind, String catalogRef,
            String secondaryStores,
            String physicalName, String pollHint, String validationKind, String flowSteps,
            List<ConsistencyHintView> consistencyHints
    ) {}

    public record FlowGateView(
            String guardedSymbolFqn, String guardedFlowStep, String gateKind,
            String gateKey, String requiredValue, String effectWhenFail,
            String testPrecondition, String evidenceSource, double confidence,
            String liveValue, String liveStatus, Instant liveSnapshotAt, String liveEvidence
    ) {
        public static FlowGateView fromDb(
                String guardedSymbolFqn, String guardedFlowStep, String gateKind,
                String gateKey, String requiredValue, String effectWhenFail,
                String testPrecondition, String evidenceSource, double confidence) {
            return new FlowGateView(
                    guardedSymbolFqn, guardedFlowStep, gateKind, gateKey, requiredValue,
                    effectWhenFail, testPrecondition, evidenceSource, confidence,
                    null, "UNKNOWN", null, null);
        }

        public FlowGateView withLive(
                String liveValue, String liveStatus, Instant liveSnapshotAt, String liveEvidence) {
            return new FlowGateView(
                    guardedSymbolFqn, guardedFlowStep, gateKind, gateKey, requiredValue,
                    effectWhenFail, testPrecondition, evidenceSource, confidence,
                    liveValue, liveStatus, liveSnapshotAt, liveEvidence);
        }
    }

    public record ValidationHintView(
            String flowStep, String hintKind, String hintValue,
            String linkedSymbolFqn, String envLane
    ) {}

    public record LiveVerificationView(
            String status,
            String expectedTopicShortId,
            String liveTopicFullName,
            String liveTopicShortId,
            Instant verifiedAt,
            String evidenceSource
    ) {}

    public record PubSubOrgView(
            String serviceId, String repo, String serviceName,
            String resourceKind, String shortId, String envLane, String role,
            String springKey, String fullResourceId, String moduleName,
            String linkedClassFqn, String workloadName, String evidenceSource, double confidence,
            String transport,
            List<ConsistencyHintView> consistencyHints,
            List<EntryFlowService.EntryTriggerView> inboundTriggers,
            LiveVerificationView liveVerification
    ) {
        public PubSubOrgView withConsistencyHints(List<ConsistencyHintView> hints) {
            return new PubSubOrgView(
                    serviceId, repo, serviceName, resourceKind, shortId, envLane, role,
                    springKey, fullResourceId, moduleName, linkedClassFqn, workloadName,
                    evidenceSource, confidence, transport, hints != null ? hints : List.of(), inboundTriggers,
                    liveVerification);
        }

        public PubSubOrgView withInboundTriggers(List<EntryFlowService.EntryTriggerView> triggers) {
            return new PubSubOrgView(
                    serviceId, repo, serviceName, resourceKind, shortId, envLane, role,
                    springKey, fullResourceId, moduleName, linkedClassFqn, workloadName,
                    evidenceSource, confidence, transport, consistencyHints,
                    triggers != null ? triggers : List.of(), liveVerification);
        }

        public PubSubOrgView withLiveVerification(LiveVerificationView live) {
            return new PubSubOrgView(
                    serviceId, repo, serviceName, resourceKind, shortId, envLane, role,
                    springKey, fullResourceId, moduleName, linkedClassFqn, workloadName,
                    evidenceSource, confidence, transport, consistencyHints, inboundTriggers, live);
        }
    }

    public record CrossRepoHop(
            int order, String topicShortId, String transport,
            List<PubSubOrgView> publishers, List<PubSubOrgView> subscribers,
            List<TerminalContinuationView> terminalContinuations
    ) {
        public CrossRepoHop(
                int order, String topicShortId,
                List<PubSubOrgView> publishers, List<PubSubOrgView> subscribers) {
            this(order, topicShortId, hopTransport(publishers, subscribers),
                    publishers, subscribers, List.of());
        }

        public CrossRepoHop(
                int order, String topicShortId,
                List<PubSubOrgView> publishers, List<PubSubOrgView> subscribers,
                List<TerminalContinuationView> terminalContinuations) {
            this(order, topicShortId, hopTransport(publishers, subscribers),
                    publishers, subscribers, terminalContinuations);
        }

        private static String hopTransport(
                List<PubSubOrgView> publishers, List<PubSubOrgView> subscribers) {
            List<PubSubOrgView> combined = new ArrayList<>();
            if (publishers != null) combined.addAll(publishers);
            if (subscribers != null) combined.addAll(subscribers);
            return MessagingTransportUtil.resolveHopTransport(combined);
        }
    }

    public record TerminalContinuationView(
            String triggerKind,
            String cronJobName,
            String bqDataset,
            String bqTable,
            String partnerVariant,
            String serviceId,
            String repo,
            String schedule,
            String evidenceSource,
            String note
    ) {}

    public record CrossRepoFlowReport(
            String orgId, String startTopic, String envLane,
            List<CrossRepoHop> hops, List<FlowGap> gaps,
            List<ConsistencyHintView> consistencyHints,
            List<String> indexedServiceIds,
            List<String> missingBundleRepos,
            List<ExternalEndpointView> externalEndpoints,
            String livePubSubStatus,
            Integer livePubSubVerifiedCount,
            Integer livePubSubSkippedCount,
            List<CrossRepoHopSummary> hopSummaries,
            List<String> narrative,
            String followMode,
            List<String> traceWarnings,
            Integer skippedExpansionCount
    ) {}

    public record ExternalEndpointView(
            String endpointId,
            String partnerSlug,
            String operation,
            String httpMethod,
            String urlTemplate,
            String urlResolved,
            String envLane,
            String boundary,
            String configKey,
            String callerClassFqn,
            String clientClassFqn,
            String flowStep,
            String authScheme,
            String evidenceSource,
            double confidence,
            String serviceId,
            String serviceName
    ) {}
}
