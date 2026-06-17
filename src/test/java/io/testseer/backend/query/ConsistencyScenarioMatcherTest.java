package io.testseer.backend.query;

import io.testseer.backend.config.MessagingRulePack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConsistencyScenarioMatcherTest {

    @Test
    void coTable_invariant_rejects_readOnlyPrimary() {
        var scenario = rulePackScenario(
                "offer-pidmap-gate",
                "CO_TABLE_INVARIANT",
                "FLOW_STEP",
                "Offer,OfferPidMap",
                "[{\"physicalName\":\"Offer\",\"role\":\"PRIMARY\"},"
                        + "{\"physicalName\":\"OfferPidMap\",\"role\":\"REQUIRED_CHILD\"}]");

        var rule = new MessagingRulePack.ConsistencyRule(
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
                null, null, List.of());

        var ctx = new ConsistencyScenarioMatcher.TouchpointContext(
                "com.example.GaloOfferReader",
                "readOffers",
                Set.of("GALO_READ"),
                List.of(dataAccess("com.example.GaloOfferReader", "readOffers", "READ", "Offer")));

        assertThat(ConsistencyScenarioMatcher.matches(scenario, rule, ctx)).isFalse();
    }

    @Test
    void coTable_invariant_accepts_coWriteBothTables() {
        var scenario = rulePackScenario(
                "offer-pidmap-gate",
                "CO_TABLE_INVARIANT",
                "FLOW_STEP",
                "Offer",
                "[{\"physicalName\":\"Offer\",\"role\":\"PRIMARY\"},"
                        + "{\"physicalName\":\"OfferPidMap\",\"role\":\"REQUIRED_CHILD\"}]");

        var rule = new MessagingRulePack.ConsistencyRule(
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
                null, null, List.of());

        var ctx = new ConsistencyScenarioMatcher.TouchpointContext(
                "com.example.HyveeOfferSyncHandler",
                "sync",
                Set.of("HYVEE_ADAPTER"),
                List.of(
                        dataAccess("com.example.HyveeOfferSyncHandler", "sync", "WRITE", "Offer"),
                        dataAccessWithEntity("sync", "WRITE", "offer_pid_mapping", "offer_pid_mapping",
                                "com.quotient.platform.data.OfferPidMapEntity",
                                "com.quotient.data.PidMapRepository")));

        assertThat(ConsistencyScenarioMatcher.matches(scenario, rule, ctx)).isTrue();
    }

    @Test
    void coTable_invariant_rejects_writePrimaryOnly() {
        var scenario = rulePackScenario(
                "offer-pidmap-gate",
                "CO_TABLE_INVARIANT",
                "FLOW_STEP",
                "Offer",
                "[{\"physicalName\":\"Offer\",\"role\":\"PRIMARY\"},"
                        + "{\"physicalName\":\"OfferPidMap\",\"role\":\"REQUIRED_CHILD\"}]");

        var rule = new MessagingRulePack.ConsistencyRule(
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
                List.of(),
                null, null, List.of());

        var ctx = new ConsistencyScenarioMatcher.TouchpointContext(
                "com.example.OfferIngestionHandler",
                "ingest",
                Set.of("HYVEE_ADAPTER"),
                List.of(dataAccess("com.example.OfferIngestionHandler", "ingest", "WRITE", "Offer")));

        assertThat(ConsistencyScenarioMatcher.matches(scenario, rule, ctx)).isFalse();
    }

    @Test
    void matches_rulePackScenario_whenFlowStepUnknown_butTableTouchesParticipant() {
        var scenario = rulePackScenario(
                "offer-projection-split",
                "PROJECTION_SPLIT",
                "FLOW_STEP",
                "Offer,OfferPurchaseRequirements",
                "[{\"physicalName\":\"Offer\"},{\"physicalName\":\"OfferPurchaseRequirements\"}]");

        var rule = new MessagingRulePack.ConsistencyRule(
                "PROJECTION_SPLIT",
                List.of("GALO_READ", "EVAL_STC"),
                "MARIADB",
                "Offer",
                List.of(new MessagingRulePack.ConsistencyParticipantRule(
                        "MARIADB", "Offer", "PRIMARY", null, null)),
                List.of(),
                List.of("offerId"),
                null, null, List.of());

        var ctx = new ConsistencyScenarioMatcher.TouchpointContext(
                "com.example.EvalConsumer",
                "evaluate",
                Set.of(),
                List.of(dataAccess("com.example.EvalConsumer", "evaluate", "READ", "Offer")));

        assertThat(ConsistencyScenarioMatcher.matches(scenario, rule, ctx)).isTrue();
    }

    @Test
    void rejects_whenFlowStepsConflict() {
        var scenario = rulePackScenario(
                "offer-pidmap-gate",
                "CO_TABLE_INVARIANT",
                "FLOW_STEP",
                "Offer",
                "[]");

        var rule = new MessagingRulePack.ConsistencyRule(
                "CO_TABLE_INVARIANT",
                List.of("GALO_READ"),
                "MARIADB",
                "Offer",
                List.of(new MessagingRulePack.ConsistencyParticipantRule(
                        "MARIADB", "Offer", "PRIMARY", null, null)),
                List.of(),
                List.of(),
                null, null, List.of());

        var ctx = new ConsistencyScenarioMatcher.TouchpointContext(
                "com.example.HyveeOfferAdapter",
                "sync",
                Set.of("HYVEE_ADAPTER"),
                List.of(dataAccess("com.example.HyveeOfferAdapter", "sync", "WRITE", "Offer")));

        assertThat(ConsistencyScenarioMatcher.matches(scenario, rule, ctx)).isFalse();
    }

    @Test
    void matches_whenTableOrEntityAndPhysicalNameNormalizeToSameToken() {
        var scenario = rulePackScenario(
                "partner-offer-call-recorder-dual-write",
                "DUAL_WRITE_SAME_HANDLER",
                "RULE_PACK",
                "PartnerOfferCallRecorder",
                "[{\"physicalName\":\"PartnerOfferCallRecorder\",\"role\":\"PRIMARY\"}]");

        var rule = new MessagingRulePack.ConsistencyRule(
                "DUAL_WRITE_SAME_HANDLER",
                List.of(),
                "MARIADB",
                "PartnerOfferCallRecorder",
                List.of(new MessagingRulePack.ConsistencyParticipantRule(
                        "MARIADB", "PartnerOfferCallRecorder", "PRIMARY", null, null)),
                List.of(),
                List.of(),
                null, null, List.of());

        var ctx = new ConsistencyScenarioMatcher.TouchpointContext(
                "com.quotient.platform.partneradapter.lib.adapter.OfferBaseAdapter",
                "recordSubmission",
                Set.of(),
                List.of(dataAccess(
                        "com.quotient.platform.partneradapter.lib.adapter.OfferBaseAdapter",
                        "recordSubmission",
                        "WRITE",
                        "PartnerOfferCallRecorder",
                        "partner_offer_call_recorder")));

        assertThat(ConsistencyScenarioMatcher.matches(scenario, rule, ctx)).isTrue();
    }

    /**
     * Regression: rule pack writes physicalName "OfferPidMap" (Java entity class name);
     * the indexer stores physical_name "offer_pid_mapping" (from @Table annotation).
     * normalize() strips underscores but "offerpidmap" ≠ "offerpidmapping".
     * The fix derives the entity class simple name from entityFqn at match time.
     */
    @Test
    void matches_whenRulePackUsesEntityClassName_butIndexerStoredSqlTableName() {
        var scenario = rulePackScenario(
                "offer-pidmap-gate",
                "CO_TABLE_INVARIANT",
                "FLOW_STEP",
                "Offer",
                "[{\"physicalName\":\"Offer\",\"role\":\"PRIMARY\"},"
                        + "{\"physicalName\":\"OfferPidMap\",\"role\":\"REQUIRED_CHILD\"}]");

        var rule = new MessagingRulePack.ConsistencyRule(
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
                null, null, List.of());

        // physical_name comes from @Table(name="offer_pid_mapping") — does NOT match
        // "OfferPidMap" after normalize. entityFqn carries the Java class name.
        var ctx = new ConsistencyScenarioMatcher.TouchpointContext(
                "com.quotient.platform.partneradapter.HyveeOfferSyncHandler",
                "sync",
                Set.of("HYVEE_ADAPTER"),
                List.of(
                        dataAccessWithEntity("sync", "WRITE", "offer", "offer",
                                "com.quotient.platform.data.OfferEntity",
                                "com.quotient.data.OfferRepository"),
                        dataAccessWithEntity("sync", "WRITE",
                                "offer_pid_mapping", "offer_pid_mapping",
                                "com.quotient.platform.data.OfferPidMapEntity",
                                "com.quotient.data.PidMapRepository")));

        assertThat(ConsistencyScenarioMatcher.matches(scenario, rule, ctx)).isTrue();
    }

    // ── entitySimpleName ────────────────────────────────────────────────────────

    @Test
    void entitySimpleName_stripsPackageAndEntitySuffix() {
        assertThat(ConsistencyScenarioMatcher.TouchpointContext
                .entitySimpleName("com.quotient.platform.data.OfferPidMapEntity"))
                .isEqualTo("OfferPidMap");
    }

    @Test
    void entitySimpleName_noEntitySuffix_returnsSimpleName() {
        assertThat(ConsistencyScenarioMatcher.TouchpointContext
                .entitySimpleName("com.quotient.platform.domain.PartnerOfferCallRecorder"))
                .isEqualTo("PartnerOfferCallRecorder");
    }

    @Test
    void entitySimpleName_null_returnsNull() {
        assertThat(ConsistencyScenarioMatcher.TouchpointContext.entitySimpleName(null)).isNull();
    }

    // ── accessorSimpleName ──────────────────────────────────────────────────────

    @Test
    void accessorSimpleName_stripsRepositorySuffix() {
        assertThat(ConsistencyScenarioMatcher.TouchpointContext
                .accessorSimpleName("com.quotient.data.PidMapRepository"))
                .isEqualTo("PidMap");
    }

    @Test
    void accessorSimpleName_stripsDaoSuffix() {
        assertThat(ConsistencyScenarioMatcher.TouchpointContext
                .accessorSimpleName("com.quotient.data.PartnerOfferCallRecorderDao"))
                .isEqualTo("PartnerOfferCallRecorder");
    }

    @Test
    void accessorSimpleName_unrecognisedSuffix_returnsNull() {
        // Template suffix not in the strip list — returns null so no noise is added
        assertThat(ConsistencyScenarioMatcher.TouchpointContext
                .accessorSimpleName("com.quotient.data.OfferJdbcTemplate"))
                .isNull();
    }

    @Test
    void accessorSimpleName_null_returnsNull() {
        assertThat(ConsistencyScenarioMatcher.TouchpointContext.accessorSimpleName(null)).isNull();
    }

    @Test
    void resolveFlowSteps_fromClassName() {
        MessagingRulePack pack = new MessagingRulePack(
                List.of(), List.of(), java.util.Map.of(), List.of(),
                List.of(new MessagingRulePack.ClassFlowStepRule("hyvee", "HYVEE_ADAPTER")),
                List.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), List.of(), java.util.Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                MessagingRulePack.CrossRepoTraceRule.empty());

        assertThat(ConsistencyScenarioMatcher.resolveFlowSteps(
                "com.example.HyveeOfferAdapter", pack))
                .containsExactly("HYVEE_ADAPTER");
    }

    private static ConsistencyQueryService.ConsistencyScenarioView rulePackScenario(
            String id, String pattern, String scopeKind, String scopeRef, String participants) {
        return new ConsistencyQueryService.ConsistencyScenarioView(
                id, pattern, scopeKind, scopeRef, "MARIADB", "Offer",
                List.of(),
                ConsistencyHintJsonParser.parseParticipants(participants),
                null,
                List.of(),
                "RULE_PACK", 0.95, null);
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

    /** Variant with entityFqn + accessorFqn populated — exercises the new name derivation. */
    private static MessagingFlowService.DataAccessView dataAccessWithEntity(
            String method, String operation, String table, String physicalName,
            String entityFqn, String accessorFqn) {
        return new MessagingFlowService.DataAccessView(
                "com.quotient.platform.partneradapter.HyveeOfferSyncHandler",
                method, operation, table, "dao", "[]", "HANDLER_LINKER+CATALOG", 0.93,
                "MARIADB", entityFqn, null, accessorFqn, "JPA_REPOSITORY", null, null,
                physicalName, null, null, null, List.of());
    }
}
