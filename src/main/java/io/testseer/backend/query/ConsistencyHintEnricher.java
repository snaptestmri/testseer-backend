package io.testseer.backend.query;

import io.testseer.backend.config.MessagingRulePack;
import io.testseer.backend.config.MessagingRulePackLoader;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

@Service
public class ConsistencyHintEnricher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConsistencyQueryService consistencyQueryService;
    private final MessagingRulePack rulePack;
    private final CrossRepoGateLinker crossRepoGateLinker;
    private final InvariantDeriver invariantDeriver;
    private final BatchIngestHintEnricher batchIngestHintEnricher;
    private final PipelineEndStateEnricher pipelineEndStateEnricher;
    private final PropagationLagEnricher propagationLagEnricher;

    public ConsistencyHintEnricher(
            ConsistencyQueryService consistencyQueryService,
            MessagingRulePackLoader rulePackLoader,
            CrossRepoGateLinker crossRepoGateLinker,
            InvariantDeriver invariantDeriver,
            BatchIngestHintEnricher batchIngestHintEnricher,
            PipelineEndStateEnricher pipelineEndStateEnricher,
            PropagationLagEnricher propagationLagEnricher) {
        this.consistencyQueryService = consistencyQueryService;
        this.rulePack = rulePackLoader.getRulePack();
        this.crossRepoGateLinker = crossRepoGateLinker;
        this.invariantDeriver = invariantDeriver;
        this.batchIngestHintEnricher = batchIngestHintEnricher;
        this.pipelineEndStateEnricher = pipelineEndStateEnricher;
        this.propagationLagEnricher = propagationLagEnricher;
    }

    public List<ConsistencyHintView> forHandler(
            String serviceId,
            String handlerFqn,
            String handlerMethod,
            List<MessagingFlowService.DataAccessView> writes) {
        return matchScenarios(serviceId, handlerFqn, handlerMethod, writes, Set.of(), null);
    }

    public List<ConsistencyHintView> forDataAccess(
            String serviceId, MessagingFlowService.DataAccessView row) {
        Set<String> flowSteps = new LinkedHashSet<>();
        addFlowStepsFromJson(flowSteps, row.flowSteps());
        flowSteps.addAll(ConsistencyScenarioMatcher.resolveFlowSteps(row.handlerClassFqn(), rulePack));
        return matchScenarios(
                serviceId, row.handlerClassFqn(), row.handlerMethod(), List.of(row), flowSteps, null);
    }

    public List<MessagingFlowService.EventFlowStep> enrichSteps(
            String serviceId, List<MessagingFlowService.EventFlowStep> steps) {
        List<MessagingFlowService.EventFlowStep> enriched = new ArrayList<>();
        for (MessagingFlowService.EventFlowStep step : steps) {
            Set<String> flowSteps = resolveStepFlowSteps(step);
            List<MessagingFlowService.DataAccessView> touchpoints = new ArrayList<>();
            touchpoints.addAll(step.reads());
            touchpoints.addAll(step.writes());
            List<ConsistencyHintView> hints = matchScenarios(
                    serviceId, step.handler(), inferMethod(step), touchpoints, flowSteps, null);
            enriched.add(new MessagingFlowService.EventFlowStep(
                    step.order(), step.workload(), step.handler(),
                    step.reads(), step.writes(), step.outbounds(), step.inbound(),
                    step.gates(), step.validationHints(), step.externalEndpoints(), hints,
                    step.inboundTriggers()
            ));
        }
        return enriched;
    }

    public List<MessagingFlowService.DataAccessView> enrichDataAccess(
            String serviceId, List<MessagingFlowService.DataAccessView> rows) {
        return rows.stream()
                .map(row -> new MessagingFlowService.DataAccessView(
                        row.handlerClassFqn(), row.handlerMethod(), row.operation(),
                        row.tableOrEntity(), row.daoMethod(), row.correlationKeys(),
                        row.evidenceSource(), row.confidence(),
                        row.storeType(), row.entityFqn(), row.domainFqn(),
                        row.accessorFqn(), row.accessorKind(), row.catalogRef(),
                        row.secondaryStores(),
                        row.physicalName(), row.pollHint(), row.validationKind(), row.flowSteps(),
                        forDataAccess(serviceId, row)
                ))
                .toList();
    }

    public List<MessagingFlowService.CrossRepoHop> enrichCrossRepoHops(
            List<MessagingFlowService.CrossRepoHop> hops,
            BiFunction<String, String, List<MessagingFlowService.DataAccessView>> dataAccessForHandler) {
        return enrichCrossRepoHops(hops, dataAccessForHandler, null);
    }

    public List<MessagingFlowService.CrossRepoHop> enrichCrossRepoHops(
            List<MessagingFlowService.CrossRepoHop> hops,
            BiFunction<String, String, List<MessagingFlowService.DataAccessView>> dataAccessForHandler,
            CrossRepoTraceContext traceContext) {
        List<MessagingFlowService.CrossRepoHop> enriched = new ArrayList<>();
        for (MessagingFlowService.CrossRepoHop hop : hops) {
            List<MessagingFlowService.PubSubOrgView> publishers = hop.publishers().stream()
                    .map(pub -> enrichPubSubNode(hop.order(), pub, dataAccessForHandler, traceContext))
                    .toList();
            List<MessagingFlowService.PubSubOrgView> subscribers = hop.subscribers().stream()
                    .map(sub -> enrichPubSubNode(hop.order(), sub, dataAccessForHandler, traceContext))
                    .toList();
            enriched.add(new MessagingFlowService.CrossRepoHop(
                    hop.order(), hop.topicShortId(), hop.transport(), publishers, subscribers,
                    hop.terminalContinuations()));
        }
        enriched = propagationLagEnricher.enrichHops(enriched, traceContext);
        if (traceContext != null) {
            List<ConsistencyHintView> endStateHints =
                    pipelineEndStateEnricher.forCrossRepoTrace(List.of(), traceContext, rulePack);
            if (!endStateHints.isEmpty() && !enriched.isEmpty()) {
                MessagingFlowService.CrossRepoHop last = enriched.get(enriched.size() - 1);
                List<MessagingFlowService.PubSubOrgView> subs = new ArrayList<>(last.subscribers());
                if (!subs.isEmpty()) {
                    MessagingFlowService.PubSubOrgView terminal = subs.get(subs.size() - 1);
                    List<ConsistencyHintView> merged = new ArrayList<>(terminal.consistencyHints());
                    merged.addAll(endStateHints);
                    subs.set(subs.size() - 1, terminal.withConsistencyHints(merged));
                    enriched.set(enriched.size() - 1, new MessagingFlowService.CrossRepoHop(
                            last.order(), last.topicShortId(), last.transport(), last.publishers(), subs,
                            last.terminalContinuations()));
                }
            }
        }
        return enriched;
    }

    private MessagingFlowService.PubSubOrgView enrichPubSubNode(
            int hopOrder,
            MessagingFlowService.PubSubOrgView node,
            BiFunction<String, String, List<MessagingFlowService.DataAccessView>> dataAccessForHandler,
            CrossRepoTraceContext traceContext) {
        if (node.linkedClassFqn() == null || node.linkedClassFqn().isBlank()) {
            return node.withConsistencyHints(List.of());
        }
        List<MessagingFlowService.DataAccessView> touchpoints = resolveTouchpoints(
                node.serviceId(), node.linkedClassFqn(), node, dataAccessForHandler, traceContext);
        Set<String> flowSteps = resolvePubSubFlowSteps(node.linkedClassFqn(), node);
        for (MessagingFlowService.DataAccessView row : touchpoints) {
            addFlowStepsFromJson(flowSteps, row.flowSteps());
        }
        String method = inferMethodFromTouchpoints(touchpoints);
        List<ConsistencyHintView> hints = matchScenarios(
                node.serviceId(), node.linkedClassFqn(), method, touchpoints, flowSteps, traceContext);
        if (traceContext != null) {
            hints = hints.stream()
                    .map(h -> crossRepoGateLinker.attachDownstreamGates(h, hopOrder, traceContext))
                    .toList();
        }
        return node.withConsistencyHints(hints);
    }

    private List<MessagingFlowService.DataAccessView> resolveTouchpoints(
            String serviceId,
            String handlerFqn,
            MessagingFlowService.PubSubOrgView node,
            BiFunction<String, String, List<MessagingFlowService.DataAccessView>> directLookup,
            CrossRepoTraceContext traceContext) {
        List<MessagingFlowService.DataAccessView> direct = directLookup.apply(serviceId, handlerFqn);
        if (!direct.isEmpty()) {
            return direct;
        }
        if (traceContext == null) {
            return direct;
        }

        List<MessagingFlowService.DataAccessView> all =
                traceContext.dataAccessByService().getOrDefault(serviceId, List.of());
        Set<String> entryFlowSteps = resolvePubSubFlowSteps(handlerFqn, node);

        List<MessagingFlowService.DataAccessView> delegates = new ArrayList<>();
        for (MessagingFlowService.DataAccessView row : all) {
            if (handlerFqn.equals(row.handlerClassFqn())) {
                delegates.add(row);
                continue;
            }
            Set<String> rowSteps = new LinkedHashSet<>();
            addFlowStepsFromJson(rowSteps, row.flowSteps());
            rowSteps.addAll(ConsistencyScenarioMatcher.resolveFlowSteps(row.handlerClassFqn(), rulePack));
            Set<String> overlap = new LinkedHashSet<>(rowSteps);
            overlap.retainAll(entryFlowSteps);
            if (!overlap.isEmpty()) {
                delegates.add(row);
            }
        }
        if (!delegates.isEmpty()) {
            return delegates;
        }

        if (!entryFlowSteps.isEmpty()) {
            return all;
        }
        return direct;
    }

    private Set<String> resolvePubSubFlowSteps(String handlerFqn, MessagingFlowService.PubSubOrgView node) {
        Set<String> flowSteps = new LinkedHashSet<>(
                ConsistencyScenarioMatcher.resolveFlowSteps(handlerFqn, rulePack));
        addFlowStepsFromTopic(flowSteps, node.shortId());
        return flowSteps;
    }

    private void addFlowStepsFromTopic(Set<String> flowSteps, String shortId) {
        if (shortId == null || shortId.isBlank() || rulePack.topicFlowSteps() == null) {
            return;
        }
        String upper = shortId.toUpperCase(Locale.ROOT);
        for (MessagingRulePack.TopicFlowStepRule rule : rulePack.topicFlowSteps()) {
            if (upper.contains(rule.match().toUpperCase(Locale.ROOT))) {
                flowSteps.add(rule.flowStep());
            }
        }
    }

    private List<ConsistencyHintView> matchScenarios(
            String serviceId,
            String handlerFqn,
            String handlerMethod,
            List<MessagingFlowService.DataAccessView> touchpoints,
            Set<String> extraFlowSteps,
            CrossRepoTraceContext traceContext) {
        List<ConsistencyQueryService.ConsistencyScenarioView> scenarios =
                consistencyQueryService.query(serviceId, null, null);
        if (scenarios.isEmpty() && (rulePack.consistencyRules() == null || rulePack.consistencyRules().isEmpty())) {
            return List.of();
        }

        Set<String> flowSteps = new LinkedHashSet<>(extraFlowSteps);
        flowSteps.addAll(ConsistencyScenarioMatcher.resolveFlowSteps(handlerFqn, rulePack));

        ConsistencyScenarioMatcher.TouchpointContext ctx =
                new ConsistencyScenarioMatcher.TouchpointContext(
                        handlerFqn, handlerMethod, flowSteps, touchpoints);

        Map<String, ConsistencyHintView> matched = new LinkedHashMap<>();
        for (ConsistencyQueryService.ConsistencyScenarioView scenario : scenarios) {
            MessagingRulePack.ConsistencyRule rule = rulePack.consistencyRules() != null
                    ? rulePack.consistencyRules().get(scenario.scenarioId()) : null;
            if (ConsistencyScenarioMatcher.matches(scenario, rule, ctx)) {
                ConsistencyHintView hint = enrichHint(
                        serviceId, scenario, rule, touchpoints, traceContext);
                matched.putIfAbsent(scenario.scenarioId(), hint);
            }
        }

        List<ConsistencyHintView> hints = HintOverlayService.overlay(
                List.copyOf(matched.values()),
                rulePack.consistencyRules(),
                ctx);

        String orgId = traceContext != null ? traceContext.orgId() : null;
        hints = batchIngestHintEnricher.maybeAttach(
                orgId, serviceId, handlerFqn, flowSteps, hints, rulePack);
        return hints;
    }

    private ConsistencyHintView enrichHint(
            String serviceId,
            ConsistencyQueryService.ConsistencyScenarioView scenario,
            MessagingRulePack.ConsistencyRule rule,
            List<MessagingFlowService.DataAccessView> touchpoints,
            CrossRepoTraceContext traceContext) {
        ConsistencyHintView base = ConsistencyHintView.fromScenario(scenario);
        List<ConsistencyInvariantHintView> derived = invariantDeriver.derive(
                serviceId, scenario, rule, touchpoints, traceContext);
        return base.withInvariants(derived);
    }

    private Set<String> resolveStepFlowSteps(MessagingFlowService.EventFlowStep step) {
        Set<String> flowSteps = new LinkedHashSet<>();
        flowSteps.addAll(ConsistencyScenarioMatcher.resolveFlowSteps(step.handler(), rulePack));
        step.gates().forEach(g -> {
            if (g.guardedFlowStep() != null && !g.guardedFlowStep().isBlank()) {
                flowSteps.add(g.guardedFlowStep());
            }
        });
        step.externalEndpoints().forEach(ep -> {
            if (ep.flowStep() != null && !ep.flowStep().isBlank()) {
                flowSteps.add(ep.flowStep());
            }
        });
        step.reads().forEach(r -> addFlowStepsFromJson(flowSteps, r.flowSteps()));
        step.writes().forEach(r -> addFlowStepsFromJson(flowSteps, r.flowSteps()));
        return flowSteps;
    }

    private static void addFlowStepsFromJson(Set<String> flowSteps, String flowStepsJson) {
        if (flowStepsJson == null || flowStepsJson.isBlank()) {
            return;
        }
        try {
            if (flowStepsJson.trim().startsWith("[")) {
                flowSteps.addAll(MAPPER.readValue(flowStepsJson, new TypeReference<List<String>>() {}));
            } else {
                flowSteps.add(flowStepsJson.trim());
            }
        } catch (Exception ignored) {
            flowSteps.add(flowStepsJson.trim());
        }
    }

    private static String inferMethodFromTouchpoints(List<MessagingFlowService.DataAccessView> touchpoints) {
        for (MessagingFlowService.DataAccessView row : touchpoints) {
            if ("WRITE".equalsIgnoreCase(row.operation()) && row.handlerMethod() != null) {
                return row.handlerMethod();
            }
        }
        for (MessagingFlowService.DataAccessView row : touchpoints) {
            if (row.handlerMethod() != null) {
                return row.handlerMethod();
            }
        }
        return null;
    }

    private static String inferMethod(MessagingFlowService.EventFlowStep step) {
        if (!step.writes().isEmpty()) {
            return step.writes().get(0).handlerMethod();
        }
        if (!step.reads().isEmpty()) {
            return step.reads().get(0).handlerMethod();
        }
        return null;
    }
}
