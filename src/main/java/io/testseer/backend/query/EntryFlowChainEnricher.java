package io.testseer.backend.query;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * TRG-12: links entry-flow handler steps to Option C messaging trace and outbound externals.
 */
@Service
public class EntryFlowChainEnricher {

    private final JdbcClient db;
    private final MessagingFlowService messagingFlowService;

    public EntryFlowChainEnricher(JdbcClient db, MessagingFlowService messagingFlowService) {
        this.db = db;
        this.messagingFlowService = messagingFlowService;
    }

    public EntryFlowChain enrich(
            String serviceId,
            String orgId,
            EntryFlowService.EntryTriggerView trigger,
            String env,
            boolean includeMessaging,
            boolean includeExternal,
            boolean crossRepo,
            int maxHops) {

        if (!includeMessaging && !includeExternal) {
            return EntryFlowChain.empty();
        }

        String envLane = resolveEnvLane(env, trigger);
        List<MessagingFlowService.ExternalEndpointView> externalEndpoints = includeExternal
                ? queryHandlerExternals(serviceId, envLane, trigger)
                : List.of();

        if (!includeMessaging) {
            return new EntryFlowChain(null, null, null, externalEndpoints);
        }

        String traceShortId = resolveTraceShortId(serviceId, trigger, envLane);
        if (traceShortId == null || traceShortId.isBlank()) {
            return new EntryFlowChain(null, null, null, externalEndpoints);
        }

        String canonicalTopicShortId = resolveCanonicalTopicShortId(traceShortId, trigger);

        MessagingFlowService.EventFlowReport messagingFlow = messagingFlowService.traceTopicFlow(
                serviceId, traceShortId, envLane, includeExternal);

        MessagingFlowService.CrossRepoFlowReport crossRepoFlow = null;
        if (crossRepo) {
            String crossRepoTopic = canonicalTopicShortId != null ? canonicalTopicShortId : traceShortId;
            String resolvedOrg = orgId != null && !orgId.isBlank()
                    ? orgId
                    : queryOrgId(serviceId);
            if (resolvedOrg != null) {
                crossRepoFlow = messagingFlowService.traceCrossRepo(
                        resolvedOrg, crossRepoTopic, envLane, maxHops, null, includeExternal);
            }
        }

        return new EntryFlowChain(
                canonicalTopicShortId != null ? canonicalTopicShortId : traceShortId,
                messagingFlow,
                crossRepoFlow,
                externalEndpoints);
    }

    String resolveTraceShortId(
            String serviceId, EntryFlowService.EntryTriggerView trigger, String envLane) {
        if (trigger == null) {
            return null;
        }
        if ("PUBSUB_SUBSCRIBE".equals(trigger.triggerKind()) && trigger.pathPattern() != null) {
            return trigger.pathPattern();
        }
        if (trigger.linkedHandlerFqn() != null) {
            String published = queryPublishTopic(serviceId, trigger.linkedHandlerFqn(), envLane);
            if (published != null) {
                return published;
            }
        }
        return topicFromPathOrShortId(trigger.pathPattern());
    }

    static String resolveCanonicalTopicShortId(
            String traceShortId, EntryFlowService.EntryTriggerView trigger) {
        if (traceShortId != null && traceShortId.contains("_T.")) {
            return traceShortId;
        }
        if (trigger != null && "PUBSUB_SUBSCRIBE".equals(trigger.triggerKind())) {
            return topicFromSubscriptionShortId(traceShortId);
        }
        return topicFromSubscriptionShortId(traceShortId);
    }

    String resolveTopicShortId(
            String serviceId, EntryFlowService.EntryTriggerView trigger, String envLane) {
        return resolveCanonicalTopicShortId(resolveTraceShortId(serviceId, trigger, envLane), trigger);
    }

    private List<MessagingFlowService.ExternalEndpointView> queryHandlerExternals(
            String serviceId,
            String envLane,
            EntryFlowService.EntryTriggerView trigger) {
        if (trigger == null || trigger.linkedHandlerFqn() == null) {
            return List.of();
        }
        return messagingFlowService.queryExternalEndpoints(serviceId, envLane, null, null).stream()
                .filter(ep -> trigger.linkedHandlerFqn().equals(ep.callerClassFqn())
                        || (trigger.flowStep() != null && trigger.flowStep().equals(ep.flowStep())))
                .toList();
    }

    private String queryPublishTopic(String serviceId, String handlerFqn, String envLane) {
        return db.sql("""
                SELECT short_id FROM pubsub_resource_facts
                WHERE service_id = :svcId AND role = 'PUBLISH'
                  AND linked_class_fqn = :handler
                ORDER BY short_id
                LIMIT 1
                """)
                .param("svcId", serviceId)
                .param("handler", handlerFqn)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private String queryOrgId(String serviceId) {
        return db.sql("SELECT org_id FROM service_registry WHERE service_id = :svcId LIMIT 1")
                .param("svcId", serviceId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    static String topicFromSubscriptionShortId(String shortId) {
        if (shortId == null || shortId.isBlank()) {
            return null;
        }
        if (shortId.contains("_S.")) {
            return shortId.replace("_S.", "_T.");
        }
        return null;
    }

    static String topicFromPathOrShortId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.contains("_T.")) {
            return value;
        }
        return topicFromSubscriptionShortId(value);
    }

    static String resolveEnvLane(String env, EntryFlowService.EntryTriggerView trigger) {
        if (env != null && !env.isBlank() && !"unknown".equals(env)) {
            return env;
        }
        if (trigger != null && trigger.envLane() != null && !trigger.envLane().isBlank()) {
            return trigger.envLane();
        }
        return env != null ? env : "unknown";
    }

    public record EntryFlowChain(
            String messagingTopicShortId,
            MessagingFlowService.EventFlowReport messagingFlow,
            MessagingFlowService.CrossRepoFlowReport crossRepoFlow,
            List<MessagingFlowService.ExternalEndpointView> externalEndpoints
    ) {
        static EntryFlowChain empty() {
            return new EntryFlowChain(null, null, null, List.of());
        }
    }
}
