package io.testseer.backend.query;

import io.testseer.backend.config.MessagingRulePack;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Matches indexed or rule-pack consistency scenarios to trace touchpoints. */
final class ConsistencyScenarioMatcher {

    private static final Set<String> CURATED_SCOPE_KINDS = Set.of("RULE_PACK", "FLOW_STEP");

    private ConsistencyScenarioMatcher() {}

    record TouchpointContext(
            String handlerFqn,
            String handlerMethod,
            Collection<String> flowSteps,
            List<MessagingFlowService.DataAccessView> touchpoints
    ) {
        String handlerKey() {
            if (handlerFqn != null && handlerMethod != null) {
                return handlerFqn + "#" + handlerMethod;
            }
            return handlerFqn;
        }

        Set<String> touchedTables() {
            Set<String> tables = new LinkedHashSet<>();
            for (MessagingFlowService.DataAccessView row : touchpoints) {
                addTable(tables, row.tableOrEntity());
                addTable(tables, row.physicalName());
                // Derive entity class name from indexed FQN so rule-pack physicalNames
                // written as Java class names (e.g. "OfferPidMap") match even when the
                // SQL table name differs ("offer_pid_mapping").
                // entityFqn is stored at index time when the catalog join succeeds; no
                // schema change or extra indexing step required.
                addTable(tables, entitySimpleName(row.entityFqn()));
                // Accessor class name stripped of Repository/Dao/Repo suffix gives yet
                // another candidate (e.g. PidMapRepository → PidMap).
                addTable(tables, accessorSimpleName(row.accessorFqn()));
            }
            return tables;
        }

        private static void addTable(Set<String> tables, String name) {
            if (name != null && !name.isBlank()) {
                tables.add(normalize(name));
            }
        }

        /**
         * Extracts the entity class simple name from a fully-qualified class name and
         * strips the common "Entity" suffix used in JPA / Spring Data naming.
         * {@code com.quotient.data.OfferPidMapEntity} → {@code "OfferPidMap"}
         * Returns {@code null} when {@code fqn} is null or blank.
         */
        static String entitySimpleName(String fqn) {
            if (fqn == null || fqn.isBlank()) return null;
            int dot = fqn.lastIndexOf('.');
            String simple = dot >= 0 ? fqn.substring(dot + 1) : fqn;
            return simple.endsWith("Entity") ? simple.substring(0, simple.length() - 6) : simple;
        }

        /**
         * Extracts a table-name hint from an accessor FQN by stripping the
         * Repository / Dao / Repo suffix from the simple class name.
         * {@code com.quotient.data.PidMapRepository} → {@code "PidMap"}
         * Returns {@code null} when {@code fqn} is null or blank.
         */
        static String accessorSimpleName(String fqn) {
            if (fqn == null || fqn.isBlank()) return null;
            int dot = fqn.lastIndexOf('.');
            String simple = dot >= 0 ? fqn.substring(dot + 1) : fqn;
            if (simple.endsWith("Repository")) return simple.substring(0, simple.length() - 10);
            if (simple.endsWith("Dao"))        return simple.substring(0, simple.length() - 3);
            if (simple.endsWith("Repo"))       return simple.substring(0, simple.length() - 4);
            return null; // not an accessor class name we recognise — don't add noise
        }
    }

    static Set<String> resolveFlowSteps(String handlerFqn, MessagingRulePack pack) {
        Set<String> steps = new LinkedHashSet<>();
        if (handlerFqn == null || handlerFqn.isBlank()) {
            return steps;
        }
        String haystack = handlerFqn.toLowerCase(Locale.ROOT);
        for (MessagingRulePack.ClassFlowStepRule rule : pack.classFlowStepRules()) {
            if (haystack.contains(rule.match().toLowerCase(Locale.ROOT))) {
                steps.add(rule.flowStep());
            }
        }
        for (MessagingRulePack.ClassFlowStepRule rule : pack.classFlowSteps()) {
            if (haystack.contains(rule.match().toLowerCase(Locale.ROOT))) {
                steps.add(rule.flowStep());
            }
        }
        return steps;
    }

    static boolean matches(
            ConsistencyQueryService.ConsistencyScenarioView scenario,
            MessagingRulePack.ConsistencyRule rule,
            TouchpointContext ctx) {
        if (!flowStepConstraintOk(rule, ctx.flowSteps())) {
            return false;
        }

        String pattern = scenario.pattern() != null
                ? scenario.pattern().toUpperCase(Locale.ROOT) : "";
        if ("CO_TABLE_INVARIANT".equals(pattern) || "MULTI_TABLE_DOMAIN".equals(pattern)
                || "CROSS_STORE_WRITE".equals(pattern)) {
            return allRequiredParticipantsWritten(scenario, ctx);
        }

        if (handlerScopeMatches(scenario, ctx)) {
            return true;
        }
        Set<String> touched = ctx.touchedTables();
        if (tableMatchesScenario(scenario, touched, pattern)) {
            return true;
        }
        for (MessagingFlowService.DataAccessView row : ctx.touchpoints()) {
            if (rowMatchesScenario(scenario, ctx.handlerKey(), row, pattern)) {
                return true;
            }
        }
        if (flowStepOnlyRulePackMatch(scenario, rule, ctx)) {
            return true;
        }
        return handlerMethodMatches(scenario, ctx.handlerMethod());
    }

    /**
     * Rule-pack scenarios scoped by flow step (e.g. HYVEE_ADAPTER S-03/S-04) when the trace
     * resolved matching flow steps but touchpoints live on delegate classes.
     */
    private static boolean flowStepOnlyRulePackMatch(
            ConsistencyQueryService.ConsistencyScenarioView scenario,
            MessagingRulePack.ConsistencyRule rule,
            TouchpointContext ctx) {
        if (rule == null || rule.flowSteps() == null || rule.flowSteps().isEmpty()) {
            return false;
        }
        if (ctx.flowSteps() == null || ctx.flowSteps().isEmpty()) {
            return false;
        }
        String scopeKind = scenario.scopeKind() != null
                ? scenario.scopeKind().toUpperCase(Locale.ROOT) : "";
        if (!CURATED_SCOPE_KINDS.contains(scopeKind)) {
            return false;
        }
        String pattern = scenario.pattern() != null
                ? scenario.pattern().toUpperCase(Locale.ROOT) : "";
        if ("CO_TABLE_INVARIANT".equals(pattern) || "MULTI_TABLE_DOMAIN".equals(pattern)
                || "CROSS_STORE_WRITE".equals(pattern)) {
            return allRequiredParticipantsWritten(scenario, ctx);
        }
        if ("PROJECTION_SPLIT".equals(pattern)) {
            return true;
        }
        return false;
    }

    /**
     * CO_TABLE_INVARIANT and MULTI_TABLE_DOMAIN require every PRIMARY / REQUIRED_*
     * participant to appear in WRITE touchpoints for this handler.
     */
    static boolean allRequiredParticipantsWritten(
            ConsistencyQueryService.ConsistencyScenarioView scenario,
            TouchpointContext ctx) {
        Set<String> written = new LinkedHashSet<>();
        for (MessagingFlowService.DataAccessView row : ctx.touchpoints()) {
            if (!"WRITE".equalsIgnoreCase(row.operation())) {
                continue;
            }
            written.addAll(normalizedRowTables(row));
        }
        if (written.isEmpty()) {
            return false;
        }

        List<ConsistencyParticipantHintView> participants = scenario.participants();
        if (participants == null || participants.isEmpty()) {
            return physicalMatchesAny(scenario.primaryPhysical(), written);
        }

        boolean sawRequired = false;
        for (ConsistencyParticipantHintView p : participants) {
            String role = p.role() != null ? p.role().toUpperCase(Locale.ROOT) : "";
            boolean required = "PRIMARY".equals(role)
                    || role.startsWith("REQUIRED")
                    || "SECONDARY".equals(role);
            if (!required) {
                continue;
            }
            sawRequired = true;
            if (!physicalMatchesAny(p.physicalName(), written)) {
                return false;
            }
        }
        if (sawRequired) {
            return true;
        }
        return physicalMatchesAny(scenario.primaryPhysical(), written);
    }

    /**
     * When the rule declares flowSteps and the trace resolved at least one flowStep,
     * require an intersection. If no flowStep context exists, allow table/participant match.
     */
    private static boolean flowStepConstraintOk(
            MessagingRulePack.ConsistencyRule rule, Collection<String> ctxFlowSteps) {
        if (rule == null || rule.flowSteps() == null || rule.flowSteps().isEmpty()) {
            return true;
        }
        if (ctxFlowSteps == null || ctxFlowSteps.isEmpty()) {
            return true;
        }
        for (String ruleStep : rule.flowSteps()) {
            for (String ctxStep : ctxFlowSteps) {
                if (ruleStep.equalsIgnoreCase(ctxStep)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean handlerScopeMatches(
            ConsistencyQueryService.ConsistencyScenarioView scenario, TouchpointContext ctx) {
        if (scenario.scopeRef() == null) {
            return false;
        }
        String scope = scenario.scopeRef();
        String handlerKey = ctx.handlerKey();
        String handlerFqn = ctx.handlerFqn();
        if (handlerKey != null && scope.contains(handlerKey)) {
            return true;
        }
        if (handlerFqn != null && scope.contains(handlerFqn)) {
            return true;
        }
        if ("HANDLER".equalsIgnoreCase(scenario.scopeKind())
                && handlerFqn != null
                && scope.contains(simpleName(handlerFqn))) {
            return true;
        }
        return false;
    }

    private static boolean tableMatchesScenario(
            ConsistencyQueryService.ConsistencyScenarioView scenario,
            Set<String> touched,
            String pattern) {
        if (touched.isEmpty()) {
            return false;
        }
        if (!"CO_TABLE_INVARIANT".equals(pattern) && !"MULTI_TABLE_DOMAIN".equals(pattern)) {
            if (physicalMatchesAny(scenario.primaryPhysical(), touched)) {
                return true;
            }
        }
        if (physicalMatchesAny(scenario.scopeRef(), touched)) {
            return CURATED_SCOPE_KINDS.contains(scenario.scopeKind().toUpperCase(Locale.ROOT));
        }
        return participantTablesMatch(scenario.participants(), touched);
    }

    private static boolean rowMatchesScenario(
            ConsistencyQueryService.ConsistencyScenarioView scenario,
            String handlerKey,
            MessagingFlowService.DataAccessView row,
            String pattern) {
        if ("CO_TABLE_INVARIANT".equals(pattern) || "MULTI_TABLE_DOMAIN".equals(pattern)) {
            return false;
        }
        if (handlerScopeMatches(scenario, new TouchpointContext(
                row.handlerClassFqn(), row.handlerMethod(), List.of(), List.of(row)))) {
            return true;
        }
        if (physicalMatches(scenario.primaryPhysical(), row.tableOrEntity())
                || physicalMatches(scenario.primaryPhysical(), row.physicalName())) {
            return true;
        }
        return participantTablesMatch(scenario.participants(), normalizedRowTables(row));
    }

    private static Set<String> normalizedRowTables(MessagingFlowService.DataAccessView row) {
        Set<String> tables = new LinkedHashSet<>();
        addNormalizedTable(tables, row.tableOrEntity());
        addNormalizedTable(tables, row.physicalName());
        addNormalizedTable(tables, TouchpointContext.entitySimpleName(row.entityFqn()));
        addNormalizedTable(tables, TouchpointContext.accessorSimpleName(row.accessorFqn()));
        return tables;
    }

    private static void addNormalizedTable(Set<String> tables, String name) {
        if (name != null && !name.isBlank()) {
            tables.add(normalize(name));
        }
    }

    private static boolean participantTablesMatch(
            List<ConsistencyParticipantHintView> participants, Set<String> touched) {
        for (String physical : participantPhysicalNames(participants)) {
            if (physicalMatchesAny(physical, touched)) {
                return true;
            }
        }
        return false;
    }

    private static boolean handlerMethodMatches(
            ConsistencyQueryService.ConsistencyScenarioView scenario, String handlerMethod) {
        return handlerMethod != null
                && scenario.scopeRef() != null
                && scenario.scopeRef().contains(handlerMethod);
    }

    static List<String> participantPhysicalNames(List<ConsistencyParticipantHintView> participants) {
        if (participants == null) return List.of();
        return participants.stream()
                .map(ConsistencyParticipantHintView::physicalName)
                .filter(n -> n != null && !n.isBlank())
                .toList();
    }

    private static boolean physicalMatchesAny(String physical, Set<String> touched) {
        if (physical == null || physical.isBlank()) {
            return false;
        }
        if (physical.contains(",")) {
            for (String part : physical.split(",")) {
                if (touched.contains(normalize(part))) {
                    return true;
                }
            }
        }
        String normalized = normalize(physical);
        return touched.contains(normalized);
    }

    private static boolean physicalMatches(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return normalize(a).equals(normalize(b));
    }

    private static String normalize(String name) {
        if (name == null) {
            return "";
        }
        return name.replace("_", "").toLowerCase(Locale.ROOT);
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }
}
