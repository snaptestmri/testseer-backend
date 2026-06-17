package io.testseer.backend.query;

import io.testseer.backend.config.WorkspaceCatalogService;
import io.testseer.backend.config.WorkspaceConfig;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EntryFlowService {

    private final JdbcClient db;
    private final EntryFlowChainEnricher chainEnricher;
    private final ProcessorRoutingEnricher processorRoutingEnricher;
    private final WorkspaceCatalogService workspaceCatalogService;

    public EntryFlowService(
            @Lazy EntryFlowChainEnricher chainEnricher,
            ProcessorRoutingEnricher processorRoutingEnricher,
            JdbcClient db,
            WorkspaceCatalogService workspaceCatalogService) {
        this.db = db;
        this.chainEnricher = chainEnricher;
        this.processorRoutingEnricher = processorRoutingEnricher;
        this.workspaceCatalogService = workspaceCatalogService;
    }

    public List<EntryTriggerView> queryTriggers(
            String serviceId, String env, String triggerKind, String actor, String boundary) {
        return queryTriggers(serviceId, env, triggerKind, actor, boundary, null, null, null, null, false);
    }

    public List<EntryTriggerView> queryTriggers(
            String serviceId,
            String env,
            String triggerKind,
            String actor,
            String boundary,
            String orgId,
            String serviceModuleId,
            String sourceRootPrefix) {
        return queryTriggers(
                serviceId, env, triggerKind, actor, boundary,
                orgId, serviceModuleId, sourceRootPrefix, null, false);
    }

    public List<EntryTriggerView> queryTriggers(
            String serviceId,
            String env,
            String triggerKind,
            String actor,
            String boundary,
            String orgId,
            String serviceModuleId,
            String sourceRootPrefix,
            boolean includeWiring) {
        return queryTriggers(
                serviceId, env, triggerKind, actor, boundary,
                orgId, serviceModuleId, sourceRootPrefix, null, includeWiring);
    }

    public List<EntryTriggerView> queryTriggers(
            String serviceId,
            String env,
            String triggerKind,
            String actor,
            String boundary,
            String orgId,
            String serviceModuleId,
            String sourceRootPrefix,
            String packagePrefix,
            boolean includeWiring) {

        List<String> scopeRoots = resolveSourceRoots(orgId, serviceModuleId, sourceRootPrefix);
        StringBuilder sql = new StringBuilder("""
                SELECT trigger_id, trigger_kind, direction, env_lane, actor, boundary,
                       http_method, path_pattern, linked_handler_fqn, linked_method, flow_step,
                       source_ref, evidence_source, confidence
                FROM entry_trigger_facts
                WHERE service_id = :svcId
                """);
        if (env != null && !env.isBlank()) {
            sql.append(" AND (env_lane = :env OR env_lane = 'unknown')");
        }
        if (triggerKind != null && !triggerKind.isBlank()) sql.append(" AND trigger_kind = :kind");
        if (actor != null && !actor.isBlank()) sql.append(" AND actor = :actor");
        if (boundary != null && !boundary.isBlank()) sql.append(" AND boundary = :boundary");
        if (!includeWiring && (triggerKind == null || triggerKind.isBlank())) {
            sql.append(" AND trigger_kind <> 'SPRING_BOOT_MAIN'");
        }
        sql.append(" ORDER BY CASE WHEN trigger_kind = 'SPRING_BOOT_MAIN' THEN 1 ELSE 0 END, trigger_id, env_lane");

        var spec = db.sql(sql.toString()).param("svcId", serviceId);
        if (env != null && !env.isBlank()) spec = spec.param("env", env);
        if (triggerKind != null && !triggerKind.isBlank()) spec = spec.param("kind", triggerKind);
        if (actor != null && !actor.isBlank()) spec = spec.param("actor", actor);
        if (boundary != null && !boundary.isBlank()) spec = spec.param("boundary", boundary);

        List<EntryTriggerView> triggers = spec.query((rs, row) -> mapEntryTrigger(rs)).list();
        triggers = EntryTriggerScopeFilter.filter(triggers, scopeRoots);
        return filterByPackagePrefix(triggers, packagePrefix);
    }

    private List<EntryTriggerView> filterByPackagePrefix(
            List<EntryTriggerView> triggers, String packagePrefix) {
        if (packagePrefix == null || packagePrefix.isBlank()) {
            return triggers;
        }
        return triggers.stream()
                .filter(t -> PackagePrefixFilter.matchesTrigger(t, packagePrefix))
                .toList();
    }

    List<String> resolveSourceRoots(String orgId, String serviceModuleId, String sourceRootPrefix) {
        if (sourceRootPrefix != null && !sourceRootPrefix.isBlank()) {
            return EntryTriggerScopeFilter.normalizeRoots(List.of(sourceRootPrefix));
        }
        if (serviceModuleId != null && !serviceModuleId.isBlank() && orgId != null && !orgId.isBlank()) {
            return workspaceCatalogService.findServiceModule(orgId, serviceModuleId)
                    .map(WorkspaceConfig.ServiceModuleConfig::sourceRoots)
                    .map(EntryTriggerScopeFilter::normalizeRoots)
                    .orElse(List.of());
        }
        return List.of();
    }

    /**
     * Service-scoped exact reverse lookup (TRG-14 first-hop enrichment).
     */
    public List<EntryTriggerView> triggersForHandler(String serviceId, String handlerFqn) {
        if (serviceId == null || serviceId.isBlank()
                || handlerFqn == null || handlerFqn.isBlank()) {
            return List.of();
        }
        ParsedHandler handler = parseHandlerFqn(handlerFqn);
        return queryImpactRows(null, serviceId, handler, null, MatchTier.EXACT).stream()
                .map(EntryTriggerImpactHit::trigger)
                .toList();
    }

    /**
     * Org-scoped reverse impact: inbound triggers that fan in to {@code handlerFqn} (TRG-13).
     */
    public EntryTriggerImpactReport impactByHandler(
            String orgId, String handlerFqn, String serviceId, String env) {
        if (orgId == null || orgId.isBlank() || handlerFqn == null || handlerFqn.isBlank()) {
            return new EntryTriggerImpactReport(
                    orgId, handlerFqn, null, env, serviceId, List.of());
        }
        ParsedHandler handler = parseHandlerFqn(handlerFqn);
        List<EntryTriggerImpactHit> hits = new ArrayList<>(
                queryImpactRows(orgId, serviceId, handler, env, MatchTier.EXACT));
        if (hits.isEmpty() && handler.simpleName() != null) {
            hits.addAll(queryImpactRows(orgId, serviceId, handler, env, MatchTier.SIMPLE_NAME));
        }
        return new EntryTriggerImpactReport(
                orgId,
                handler.classFqn(),
                handler.method(),
                env,
                serviceId,
                List.copyOf(hits));
    }

    public boolean orgHasEntryTriggers(String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return false;
        }
        Long count = db.sql("""
                SELECT COUNT(*) FROM entry_trigger_facts WHERE org_id = :orgId
                """)
                .param("orgId", orgId)
                .query(Long.class)
                .single();
        return count != null && count > 0;
    }

    private List<EntryTriggerImpactHit> queryImpactRows(
            String orgId,
            String serviceId,
            ParsedHandler handler,
            String env,
            MatchTier tier) {
        StringBuilder sql = new StringBuilder("""
                SELECT t.trigger_id, t.trigger_kind, t.direction, t.env_lane, t.actor, t.boundary,
                       t.http_method, t.path_pattern, t.linked_handler_fqn, t.linked_method, t.flow_step,
                       t.source_ref, t.evidence_source, t.confidence,
                       t.service_id, sr.repo, sr.service_name
                FROM entry_trigger_facts t
                JOIN service_registry sr ON sr.service_id = t.service_id
                WHERE
                """);
        if (orgId != null && !orgId.isBlank()) {
            sql.append(" t.org_id = :orgId");
        } else {
            sql.append(" t.service_id = :svcId");
        }
        if (serviceId != null && !serviceId.isBlank()) {
            sql.append(" AND t.service_id = :svcId");
        }
        if (tier == MatchTier.EXACT) {
            sql.append(" AND t.linked_handler_fqn = :handlerClass");
            if (handler.method() != null && !handler.method().isBlank()) {
                sql.append(" AND t.linked_method = :handlerMethod");
            }
        } else {
            sql.append(" AND t.linked_handler_fqn LIKE :handlerSuffix");
        }
        if (env != null && !env.isBlank()) {
            sql.append(" AND (t.env_lane = :env OR t.env_lane = 'unknown')");
        }
        sql.append(" AND t.trigger_kind <> 'SPRING_BOOT_MAIN'");
        sql.append(" ORDER BY sr.service_name, t.trigger_kind, t.trigger_id, t.env_lane");

        var spec = db.sql(sql.toString());
        if (orgId != null && !orgId.isBlank()) {
            spec = spec.param("orgId", orgId);
        }
        if (serviceId != null && !serviceId.isBlank()) {
            spec = spec.param("svcId", serviceId);
        } else if (orgId == null || orgId.isBlank()) {
            spec = spec.param("svcId", serviceId);
        }
        if (tier == MatchTier.EXACT) {
            spec = spec.param("handlerClass", handler.classFqn());
            if (handler.method() != null && !handler.method().isBlank()) {
                spec = spec.param("handlerMethod", handler.method());
            }
        } else {
            spec = spec.param("handlerSuffix", "%." + handler.simpleName());
        }
        if (env != null && !env.isBlank()) {
            spec = spec.param("env", env);
        }

        String matchKind = tier == MatchTier.EXACT
                ? (handler.method() != null && !handler.method().isBlank() ? "METHOD" : "EXACT")
                : "SIMPLE_NAME";

        Map<String, EntryTriggerImpactHit> deduped = new LinkedHashMap<>();
        spec.query((rs, row) -> {
            EntryTriggerView trigger = mapEntryTrigger(rs);
            EntryTriggerImpactHit hit = new EntryTriggerImpactHit(
                    rs.getString("service_id"),
                    rs.getString("repo"),
                    rs.getString("service_name"),
                    matchKind,
                    trigger);
            String key = hit.serviceId() + "|" + trigger.triggerId() + "|" + trigger.envLane();
            deduped.putIfAbsent(key, hit);
            return hit;
        }).list();
        return List.copyOf(deduped.values());
    }

    public static ParsedHandler parseHandlerFqn(String handlerFqn) {
        String trimmed = handlerFqn.trim();
        int hash = trimmed.indexOf('#');
        if (hash > 0) {
            return new ParsedHandler(
                    trimmed.substring(0, hash),
                    trimmed.substring(hash + 1),
                    simpleName(trimmed.substring(0, hash)));
        }
        int lastDot = trimmed.lastIndexOf('.');
        if (lastDot > 0) {
            String candidateMethod = trimmed.substring(lastDot + 1);
            String candidateClass = trimmed.substring(0, lastDot);
            if (isJavaMethodName(candidateMethod) && candidateClass.contains(".")) {
                return new ParsedHandler(candidateClass, candidateMethod, simpleName(candidateClass));
            }
        }
        return new ParsedHandler(trimmed, null, simpleName(trimmed));
    }

    static boolean isJavaMethodName(String name) {
        return name != null
                && !name.isBlank()
                && Character.isLowerCase(name.charAt(0))
                && name.chars().allMatch(ch -> Character.isJavaIdentifierPart(ch));
    }

    private static String simpleName(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return null;
        }
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private static EntryTriggerView mapEntryTrigger(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EntryTriggerView(
                rs.getString("trigger_id"),
                rs.getString("trigger_kind"),
                rs.getString("direction"),
                rs.getString("env_lane"),
                rs.getString("actor"),
                rs.getString("boundary"),
                rs.getString("http_method"),
                rs.getString("path_pattern"),
                rs.getString("linked_handler_fqn"),
                rs.getString("linked_method"),
                rs.getString("flow_step"),
                rs.getString("source_ref"),
                rs.getString("evidence_source"),
                rs.getDouble("confidence")
        );
    }

    public EntryFlowReport traceEntryFlow(
            String serviceId, String triggerId, String path, String env) {
        return traceEntryFlow(
                serviceId, triggerId, path, env, false, false, false, null, 12, null, null, null, false);
    }

    public EntryFlowReport traceEntryFlow(
            String serviceId,
            String triggerId,
            String path,
            String env,
            boolean includeMessaging,
            boolean includeExternal,
            boolean crossRepo,
            String orgId,
            int maxHops) {
        return traceEntryFlow(
                serviceId, triggerId, path, env,
                includeMessaging, includeExternal, crossRepo, orgId, maxHops,
                null, null, null, false);
    }

    public EntryFlowReport traceEntryFlow(
            String serviceId,
            String triggerId,
            String path,
            String env,
            boolean includeMessaging,
            boolean includeExternal,
            boolean crossRepo,
            String orgId,
            int maxHops,
            String serviceModuleId,
            String sourceRootPrefix,
            String scopeOrgId) {
        return traceEntryFlow(
                serviceId, triggerId, path, env,
                includeMessaging, includeExternal, crossRepo, orgId, maxHops,
                serviceModuleId, sourceRootPrefix, scopeOrgId, false);
    }

    public EntryFlowReport traceEntryFlow(
            String serviceId,
            String triggerId,
            String path,
            String env,
            boolean includeMessaging,
            boolean includeExternal,
            boolean crossRepo,
            String orgId,
            int maxHops,
            String serviceModuleId,
            String sourceRootPrefix,
            String scopeOrgId,
            boolean includeWiring) {

        String effectiveOrgId = scopeOrgId != null && !scopeOrgId.isBlank() ? scopeOrgId : orgId;
        boolean loadWiringTriggers = includeWiring
                || (triggerId != null && triggerId.startsWith("spring-boot:"));
        List<EntryTriggerView> triggers = queryTriggers(
                serviceId, env, null, null, null,
                effectiveOrgId, serviceModuleId, sourceRootPrefix, loadWiringTriggers);
        if (triggerId != null && !triggerId.isBlank()) {
            triggers = triggers.stream().filter(t -> triggerId.equals(t.triggerId())).toList();
        } else if (path != null && !path.isBlank()) {
            String normalized = path.startsWith("/") ? path : "/" + path;
            triggers = triggers.stream()
                    .filter(t -> normalized.equals(t.pathPattern()) || (t.pathPattern() != null && t.pathPattern().startsWith(normalized)))
                    .toList();
        }

        List<EntryFlowStep> steps = new ArrayList<>();
        List<ProcessorRoutingEnricher.ProcessorRoutingStep> processorRouting = new ArrayList<>();
        int order = 1;
        for (EntryTriggerView trigger : triggers) {
            List<DataAccessSummary> reads = List.of();
            List<DataAccessSummary> writes = List.of();
            List<GateSummary> gates = List.of();

            if (trigger.linkedHandlerFqn() != null) {
                reads = queryDataAccess(serviceId, trigger.linkedHandlerFqn(), "READ");
                writes = queryDataAccess(serviceId, trigger.linkedHandlerFqn(), "WRITE");
                processorRouting.addAll(
                        processorRoutingEnricher.enrichForHandler(serviceId, trigger.linkedHandlerFqn()));
            }
            if (trigger.flowStep() != null) {
                gates = queryGates(serviceId, trigger.flowStep(), trigger.envLane());
            }

            steps.add(new EntryFlowStep(
                    order++,
                    trigger,
                    reads,
                    writes,
                    gates
            ));
        }

        EntryFlowChainEnricher.EntryFlowChain chain = EntryFlowChainEnricher.EntryFlowChain.empty();
        boolean wiringOnly = !steps.isEmpty()
                && "SPRING_BOOT_MAIN".equals(steps.get(0).trigger().triggerKind());
        if (!steps.isEmpty() && (includeMessaging || includeExternal) && !wiringOnly) {
            chain = chainEnricher.enrich(
                    serviceId,
                    orgId,
                    steps.get(0).trigger(),
                    env,
                    includeMessaging,
                    includeExternal,
                    crossRepo,
                    maxHops);
        }

        List<WiringTarget> wiringTargets = List.of();
        if (includeWiring && !steps.isEmpty() && steps.get(0).trigger().linkedHandlerFqn() != null) {
            wiringTargets = queryWiringTargets(serviceId, steps.get(0).trigger().linkedHandlerFqn());
        }

        return new EntryFlowReport(
                serviceId,
                triggerId,
                path,
                env,
                steps,
                chain.messagingTopicShortId(),
                chain.messagingFlow(),
                chain.crossRepoFlow(),
                chain.externalEndpoints(),
                List.copyOf(processorRouting),
                List.copyOf(wiringTargets));
    }

    private List<WiringTarget> queryWiringTargets(String serviceId, String mainClassFqn) {
        String fromNode = io.testseer.backend.graph.GraphNodeIds.classNode(serviceId, mainClassFqn);
        return db.sql("""
                SELECT gn.symbol_fqn
                FROM graph_edges e
                JOIN graph_nodes gn ON gn.id = e.to_node
                WHERE e.from_node = :fromNode AND e.edge_type = 'WIRES'
                ORDER BY gn.symbol_fqn
                """)
                .param("fromNode", fromNode)
                .query((rs, row) -> new WiringTarget(rs.getString("symbol_fqn"), "component_scan"))
                .list();
    }

    private List<DataAccessSummary> queryDataAccess(String serviceId, String handlerFqn, String operation) {
        return db.sql("""
                SELECT handler_class_fqn, handler_method, operation, store_type, table_or_entity
                FROM data_access_facts
                WHERE service_id = :svcId AND handler_class_fqn = :handler AND operation = :op
                ORDER BY table_or_entity
                """)
                .param("svcId", serviceId)
                .param("handler", handlerFqn)
                .param("op", operation)
                .query((rs, row) -> new DataAccessSummary(
                        rs.getString("handler_class_fqn"),
                        rs.getString("handler_method"),
                        rs.getString("operation"),
                        rs.getString("store_type"),
                        rs.getString("table_or_entity")
                ))
                .list();
    }

    private List<GateSummary> queryGates(String serviceId, String flowStep, String envLane) {
        return db.sql("""
                SELECT guarded_symbol_fqn, gate_kind, gate_key, required_value, effect_when_fail, test_precondition
                FROM flow_gate_facts
                WHERE service_id = :svcId AND guarded_flow_step = :flowStep
                  AND (env_lane = :env OR env_lane = 'unknown')
                ORDER BY gate_key
                """)
                .param("svcId", serviceId)
                .param("flowStep", flowStep)
                .param("env", envLane != null ? envLane : "unknown")
                .query((rs, row) -> new GateSummary(
                        rs.getString("guarded_symbol_fqn"),
                        rs.getString("gate_kind"),
                        rs.getString("gate_key"),
                        rs.getString("required_value"),
                        rs.getString("effect_when_fail"),
                        rs.getString("test_precondition")
                ))
                .list();
    }

    public record ParsedHandler(String classFqn, String method, String simpleName) {}

    enum MatchTier { EXACT, SIMPLE_NAME }

    public record EntryTriggerView(
            String triggerId,
            String triggerKind,
            String direction,
            String envLane,
            String actor,
            String boundary,
            String httpMethod,
            String pathPattern,
            String linkedHandlerFqn,
            String linkedMethod,
            String flowStep,
            String sourceRef,
            String evidenceSource,
            double confidence
    ) {}

    public record EntryTriggerImpactHit(
            String serviceId,
            String repo,
            String serviceName,
            String matchKind,
            EntryTriggerView trigger
    ) {}

    public record EntryTriggerImpactReport(
            String orgId,
            String handlerFqn,
            String handlerMethod,
            String envLane,
            String serviceId,
            List<EntryTriggerImpactHit> triggers
    ) {}

    public record DataAccessSummary(
            String handlerClassFqn,
            String handlerMethod,
            String operation,
            String storeType,
            String tableOrEntity
    ) {}

    public record GateSummary(
            String guardedSymbolFqn,
            String gateKind,
            String gateKey,
            String requiredValue,
            String effectWhenFail,
            String testPrecondition
    ) {}

    public record EntryFlowStep(
            int order,
            EntryTriggerView trigger,
            List<DataAccessSummary> reads,
            List<DataAccessSummary> writes,
            List<GateSummary> gates
    ) {}

    public record EntryFlowReport(
            String serviceId,
            String triggerId,
            String path,
            String envLane,
            List<EntryFlowStep> steps,
            String messagingTopicShortId,
            MessagingFlowService.EventFlowReport messagingFlow,
            MessagingFlowService.CrossRepoFlowReport crossRepoFlow,
            List<MessagingFlowService.ExternalEndpointView> externalEndpoints,
            List<ProcessorRoutingEnricher.ProcessorRoutingStep> processorRouting,
            List<WiringTarget> wiringTargets
    ) {}

    public record WiringTarget(String symbolFqn, String edgeRole) {}
}
