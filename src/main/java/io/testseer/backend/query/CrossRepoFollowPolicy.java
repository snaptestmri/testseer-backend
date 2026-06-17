package io.testseer.backend.query;

import io.testseer.backend.config.MessagingRulePack;
import io.testseer.backend.config.WorkspaceCatalogService;
import io.testseer.backend.config.WorkspaceConfig;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * BL-055: controls which subscribers expand the cross-repo BFS and which egress topics are enqueued.
 */
final class CrossRepoFollowPolicy {

    static final String MODE_RUNTIME = "runtime";
    static final String MODE_INVENTORY = "inventory";
    static final String MODE_CAUSAL = "causal";

    private static final Set<String> MANIFEST_EVIDENCE = Set.of("YAML", "MANIFEST");
    private static final Set<String> RUNTIME_EVIDENCE = Set.of(
            "JAVAPARSER", "RULE_PACK", "SPRING_INDEX", "INDEXED", "HTTP_PUBSUB");

    private final Context context;

    CrossRepoFollowPolicy(Context context) {
        this.context = context;
    }

    record Context(
            String effectiveFollowMode,
            Set<String> manifestOnlyRepos,
            Set<String> libraryServiceIds,
            Map<String, Set<String>> subscribeTopicsByServiceId,
            List<String> traceWarnings) {}

    record ExpansionResult(List<String> topics, int skippedSubscribers) {}

    static CrossRepoFollowPolicy load(
            MessagingRulePack rulePack,
            WorkspaceCatalogService workspaceCatalog,
            JdbcClient db,
            String orgId,
            String followMode,
            boolean includeManifest) {
        String resolved = normalizeFollowMode(followMode, includeManifest);
        List<String> warnings = new ArrayList<>();
        if (MODE_CAUSAL.equalsIgnoreCase(followMode) && !MODE_CAUSAL.equals(resolved)) {
            warnings.add("followMode=causal is not implemented; fell back to runtime");
        }
        Set<String> manifestRepos = resolveManifestRepos(rulePack, workspaceCatalog, orgId);
        Set<String> libraryIds = loadLibraryServiceIds(db, orgId);
        Map<String, Set<String>> subscribeTopics = loadSubscribeTopicsByService(db, orgId);
        return new CrossRepoFollowPolicy(new Context(
                resolved, manifestRepos, libraryIds, subscribeTopics, List.copyOf(warnings)));
    }

    Context context() {
        return context;
    }

    ExpansionResult expandFromSubscribers(
            List<MessagingFlowService.PubSubOrgView> subscribers,
            List<MessagingFlowService.PubSubOrgView> allFacts,
            KafkaTopicAliasIndex topicAliases,
            Set<String> visitedTopics) {
        if (subscribers == null || subscribers.isEmpty()) {
            return new ExpansionResult(List.of(), 0);
        }
        Set<String> topics = new LinkedHashSet<>();
        int skipped = 0;
        for (MessagingFlowService.PubSubOrgView sub : subscribers) {
            if (!shouldExpandFrom(sub)) {
                skipped++;
                continue;
            }
            for (String topic : egressTopicsForSubscriber(sub, allFacts, topicAliases)) {
                if (!visitedTopics.contains(topic)) {
                    topics.add(topic);
                }
            }
        }
        return new ExpansionResult(List.copyOf(topics), skipped);
    }

    boolean shouldExpandFrom(MessagingFlowService.PubSubOrgView subscriber) {
        if (subscriber == null) {
            return false;
        }
        if (MODE_INVENTORY.equals(context.effectiveFollowMode())) {
            return true;
        }
        if (subscriber.repo() != null && context.manifestOnlyRepos().contains(subscriber.repo())) {
            return false;
        }
        return isRuntimeSubscriber(subscriber);
    }

    boolean isRuntimeSubscriber(MessagingFlowService.PubSubOrgView subscriber) {
        if (hasLinkedClass(subscriber.linkedClassFqn())) {
            return true;
        }
        if (context.libraryServiceIds().contains(subscriber.serviceId())) {
            return false;
        }
        if (subscriber.repo() != null && context.manifestOnlyRepos().contains(subscriber.repo())) {
            return hasSubscribeTriggerForTopic(subscriber.serviceId(), subscriber.shortId());
        }
        if (isManifestEvidence(subscriber.evidenceSource()) && !hasLinkedClass(subscriber.linkedClassFqn())) {
            return hasSubscribeTriggerForTopic(subscriber.serviceId(), subscriber.shortId());
        }
        return hasSubscribeTriggerForTopic(subscriber.serviceId(), subscriber.shortId());
    }

    boolean isManifestOnlyRepo(String repo) {
        return repo != null && context.manifestOnlyRepos().contains(repo);
    }

    private List<String> egressTopicsForSubscriber(
            MessagingFlowService.PubSubOrgView subscriber,
            List<MessagingFlowService.PubSubOrgView> allFacts,
            KafkaTopicAliasIndex topicAliases) {
        if (MODE_CAUSAL.equals(context.effectiveFollowMode())) {
            return List.of();
        }
        return allFacts.stream()
                .filter(p -> subscriber.serviceId().equals(p.serviceId()))
                .filter(p -> "PUBLISH".equals(p.role()))
                .map(MessagingFlowService.PubSubOrgView::shortId)
                .map(topicAliases::canonical)
                .distinct()
                .toList();
    }

    private boolean hasSubscribeTriggerForTopic(String serviceId, String topicShortId) {
        if (serviceId == null || topicShortId == null) {
            return false;
        }
        Set<String> topics = context.subscribeTopicsByServiceId().get(serviceId);
        if (topics == null || topics.isEmpty()) {
            return false;
        }
        String stem = topicStem(topicShortId);
        for (String candidate : topics) {
            if (candidate == null) continue;
            if (candidate.equals(topicShortId)
                    || candidate.equalsIgnoreCase(topicShortId)
                    || candidate.contains(stem)
                    || topicShortId.contains(topicStem(candidate))) {
                return true;
            }
        }
        return false;
    }

    private static String topicStem(String topic) {
        if (topic == null) return "";
        int idx = topic.indexOf("_T.");
        return idx >= 0 ? topic.substring(idx + 3) : topic;
    }

    private static boolean hasLinkedClass(String fqn) {
        return fqn != null && !fqn.isBlank();
    }

    private static boolean isManifestEvidence(String evidenceSource) {
        return evidenceSource != null
                && MANIFEST_EVIDENCE.contains(evidenceSource.toUpperCase(Locale.ROOT));
    }

    static boolean isRuntimePublisher(MessagingFlowService.PubSubOrgView publisher, Set<String> manifestRepos) {
        if (publisher == null) {
            return false;
        }
        if (hasLinkedClass(publisher.linkedClassFqn())) {
            return true;
        }
        if (publisher.evidenceSource() != null) {
            String src = publisher.evidenceSource().toUpperCase(Locale.ROOT);
            if (RUNTIME_EVIDENCE.contains(src)) {
                return true;
            }
        }
        if (publisher.repo() != null && manifestRepos.contains(publisher.repo())) {
            return false;
        }
        return !isManifestEvidence(publisher.evidenceSource());
    }

    static boolean isManifestOnlyPublisher(
            List<MessagingFlowService.PubSubOrgView> publishers, Set<String> manifestRepos) {
        if (publishers == null || publishers.isEmpty()) {
            return false;
        }
        return publishers.stream().allMatch(p ->
                isManifestEvidence(p.evidenceSource())
                        && !hasLinkedClass(p.linkedClassFqn())
                        || (p.repo() != null && manifestRepos.contains(p.repo())
                                && !hasLinkedClass(p.linkedClassFqn())));
    }

    private static String normalizeFollowMode(String followMode, boolean includeManifest) {
        if (includeManifest) {
            return MODE_INVENTORY;
        }
        if (followMode == null || followMode.isBlank()) {
            return MODE_RUNTIME;
        }
        String mode = followMode.trim().toLowerCase(Locale.ROOT);
        if (MODE_INVENTORY.equals(mode)) {
            return MODE_INVENTORY;
        }
        if (MODE_CAUSAL.equals(mode)) {
            return MODE_RUNTIME;
        }
        return MODE_RUNTIME;
    }

    private static Set<String> resolveManifestRepos(
            MessagingRulePack rulePack,
            WorkspaceCatalogService workspaceCatalog,
            String orgId) {
        Set<String> repos = new LinkedHashSet<>();
        MessagingRulePack.CrossRepoTraceRule trace = rulePack.crossRepoTrace();
        if (trace != null) {
            if (trace.manifestOnlyRepos() != null) {
                repos.addAll(trace.manifestOnlyRepos());
            }
            if (trace.catalogOnlyRepos() != null) {
                repos.addAll(trace.catalogOnlyRepos());
            }
        }
        WorkspaceConfig config = workspaceCatalog.config(orgId);
        if (config.catalogLibraries() != null) {
            for (WorkspaceConfig.CatalogLibraryConfig lib : config.catalogLibraries()) {
                if (!lib.indexDdl() && lib.repo() != null) {
                    repos.add(lib.repo());
                }
            }
        }
        return Set.copyOf(repos);
    }

    private static Set<String> loadLibraryServiceIds(JdbcClient db, String orgId) {
        return Set.copyOf(db.sql("""
                        SELECT service_id FROM service_registry
                        WHERE org_id = :orgId AND module_type = 'library'
                        """)
                .param("orgId", orgId)
                .query(String.class)
                .list());
    }

    private static Map<String, Set<String>> loadSubscribeTopicsByService(JdbcClient db, String orgId) {
        Map<String, Set<String>> map = new java.util.LinkedHashMap<>();
        db.sql("""
                        SELECT service_id, trigger_id, path_pattern
                        FROM entry_trigger_facts
                        WHERE org_id = :orgId
                          AND trigger_kind IN ('PUBSUB_SUBSCRIBE', 'KAFKA_SUBSCRIBE')
                        """)
                .param("orgId", orgId)
                .query((rs, row) -> new Object[] {
                        rs.getString("service_id"),
                        rs.getString("trigger_id"),
                        rs.getString("path_pattern")
                })
                .list()
                .forEach(row -> {
                    String serviceId = (String) row[0];
                    Set<String> topics = map.computeIfAbsent(serviceId, k -> new LinkedHashSet<>());
                    addTopicToken(topics, (String) row[1]);
                    addTopicToken(topics, (String) row[2]);
                });
        return Map.copyOf(map);
    }

    private static void addTopicToken(Set<String> topics, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        topics.add(raw);
        for (String part : raw.split("[:/]")) {
            if (part.contains("_T.") || part.contains("_S.") || part.contains(".")) {
                topics.add(part);
            }
        }
    }
}
