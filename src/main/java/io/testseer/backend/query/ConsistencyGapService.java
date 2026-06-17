package io.testseer.backend.query;

import io.testseer.backend.config.GapStrategy;
import io.testseer.backend.config.MessagingRulePack;
import io.testseer.backend.config.MessagingRulePackLoader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class ConsistencyGapService {

    private final JdbcClient db;
    private final MessagingRulePack rulePack;

    public ConsistencyGapService(JdbcClient db, MessagingRulePackLoader rulePackLoader) {
        this.db = db;
        this.rulePack = rulePackLoader.getRulePack();
    }

    public List<ConsistencyGapView> computeGaps(String orgId, String serviceId) {
        List<ConsistencyGapView> gaps = new ArrayList<>();
        gaps.addAll(undocumentedCoWrites(orgId, serviceId));
        gaps.addAll(orphanRulePackRules(orgId, serviceId));
        gaps.addAll(unlinkedMirrors(orgId, serviceId));
        return gaps;
    }

    private List<ConsistencyGapView> undocumentedCoWrites(String orgId, String serviceId) {
        return db.sql("""
                SELECT handler_class_fqn, handler_method, COUNT(*) AS write_count
                FROM data_access_facts
                WHERE org_id = :orgId AND service_id = :svcId AND operation = 'WRITE'
                GROUP BY handler_class_fqn, handler_method
                HAVING COUNT(*) >= 2
                """)
                .param("orgId", orgId)
                .param("svcId", serviceId)
                .query((rs, row) -> {
                    String handler = rs.getString("handler_class_fqn") + "#" + rs.getString("handler_method");
                    boolean hasScenario = db.sql("""
                            SELECT COUNT(*) FROM consistency_scenario_facts
                            WHERE org_id = :orgId AND service_id = :svcId
                              AND pattern IN (
                                  'DUAL_WRITE', 'DUAL_WRITE_SAME_HANDLER',
                                  'MULTI_TABLE_DOMAIN', 'CROSS_STORE_WRITE')
                              AND scope_ref LIKE :handlerLike
                            """)
                            .param("orgId", orgId)
                            .param("svcId", serviceId)
                            .param("handlerLike", "%" + handler + "%")
                            .query(Integer.class)
                            .single() > 0;
                    if (hasScenario) return null;
                    return new ConsistencyGapView(
                            "UNDOCUMENTED_DUAL_WRITE",
                            handler,
                            null,
                            "Handler performs multiple writes without indexed consistency scenario");
                })
                .list()
                .stream()
                .filter(g -> g != null)
                .toList();
    }

    private List<ConsistencyGapView> orphanRulePackRules(String orgId, String serviceId) {
        if (rulePack.consistencyRules() == null || rulePack.consistencyRules().isEmpty()) {
            return List.of();
        }
        Set<String> indexed = new LinkedHashSet<>(db.sql("""
                SELECT scenario_id FROM consistency_scenario_facts
                WHERE org_id = :orgId AND service_id = :svcId
                """)
                .param("orgId", orgId)
                .param("svcId", serviceId)
                .query(String.class)
                .list());

        List<ConsistencyGapView> gaps = new ArrayList<>();
        rulePack.consistencyRules().forEach((id, rule) -> {
            if (!expectsIndexedScenario(rule)) {
                return;
            }
            if (!indexed.contains(id)) {
                gaps.add(new ConsistencyGapView(
                        "ORPHAN_RULE_PACK",
                        id,
                        rule.pattern(),
                        "Rule pack consistency rule has no matching indexed scenario for service"));
            }
        });
        return gaps;
    }

    /** Curated domain rules use gapStrategy; inferrable patterns default to INDEXED. */
    static boolean expectsIndexedScenario(MessagingRulePack.ConsistencyRule rule) {
        return GapStrategy.of(rule) == GapStrategy.INDEXED;
    }

    private List<ConsistencyGapView> unlinkedMirrors(String orgId, String serviceId) {
        return db.sql("""
                SELECT entity_fqn, secondary_stores
                FROM data_access_facts
                WHERE org_id = :orgId AND service_id = :svcId
                  AND secondary_stores IS NOT NULL AND secondary_stores <> '[]'
                """)
                .param("orgId", orgId)
                .param("svcId", serviceId)
                .query((rs, row) -> {
                    String entityFqn = rs.getString("entity_fqn");
                    boolean hasMirrorScenario = db.sql("""
                            SELECT COUNT(*) FROM consistency_scenario_facts
                            WHERE org_id = :orgId AND service_id = :svcId
                              AND pattern IN ('ASYNC_MIRROR', 'MIRROR')
                              AND (scope_ref LIKE :entityLike OR primary_physical IS NOT NULL)
                            """)
                            .param("orgId", orgId)
                            .param("svcId", serviceId)
                            .param("entityLike", "%" + (entityFqn != null ? entityFqn : "") + "%")
                            .query(Integer.class)
                            .single() > 0;
                    if (hasMirrorScenario) return null;
                    return new ConsistencyGapView(
                            "UNLINKED_MIRROR",
                            entityFqn,
                            rs.getString("secondary_stores"),
                            "Secondary store mirror without ASYNC_MIRROR consistency scenario");
                })
                .list()
                .stream()
                .filter(g -> g != null)
                .toList();
    }

    public record ConsistencyGapView(
            String gapType,
            String scopeRef,
            String detail,
            String description
    ) {}
}
