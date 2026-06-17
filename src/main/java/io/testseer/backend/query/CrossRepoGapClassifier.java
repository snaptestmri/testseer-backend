package io.testseer.backend.query;

import io.testseer.backend.config.MessagingRulePack;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * BL-057: classifies missing-subscriber situations into actionable gap types.
 */
final class CrossRepoGapClassifier {

    private final MessagingRulePack.CrossRepoTraceRule rules;
    private final Set<String> manifestRepos;

    CrossRepoGapClassifier(MessagingRulePack.CrossRepoTraceRule rules, Set<String> manifestRepos) {
        this.rules = rules != null ? rules : MessagingRulePack.CrossRepoTraceRule.empty();
        this.manifestRepos = manifestRepos != null ? manifestRepos : Set.of();
    }

    record TopicContext(
            String topicShortId,
            int hopOrder,
            List<MessagingFlowService.PubSubOrgView> publishers,
            List<MessagingFlowService.PubSubOrgView> subscribers,
            List<String> missingBundleRepos) {}

    Optional<MessagingFlowService.FlowGap> classifyMissingSubscriber(TopicContext ctx) {
        if (ctx.subscribers() != null && !ctx.subscribers().isEmpty()) {
            return Optional.empty();
        }
        if (ctx.publishers() == null || ctx.publishers().isEmpty()) {
            return Optional.empty();
        }
        if (CrossRepoFollowPolicy.isManifestOnlyPublisher(ctx.publishers(), manifestRepos)) {
            return Optional.of(gap(
                    "MANIFEST_ONLY_PUBLISHER",
                    "Manifest/catalog publisher only for topic " + ctx.topicShortId()
                            + "; no runtime consumer expected in bundle",
                    ctx));
        }
        Optional<MessagingRulePack.TerminalTopicRule> terminal = matchTerminalTopic(ctx.topicShortId());
        if (terminal.isPresent()) {
            MessagingRulePack.TerminalTopicRule rule = terminal.get();
            String note = rule.note() != null ? rule.note() : "Partner/external topic boundary";
            return Optional.of(gap(
                    "TERMINAL_EXTERNAL",
                    note + " (topic=" + ctx.topicShortId() + ")",
                    ctx));
        }
        boolean runtimePublisher = ctx.publishers().stream()
                .anyMatch(p -> CrossRepoFollowPolicy.isRuntimePublisher(p, manifestRepos));
        if (!runtimePublisher) {
            return Optional.of(gap(
                    "MANIFEST_ONLY_PUBLISHER",
                    "No runtime publisher indexed for topic " + ctx.topicShortId(),
                    ctx));
        }
        if (ctx.missingBundleRepos() != null && !ctx.missingBundleRepos().isEmpty()) {
            return Optional.of(gap(
                    "NO_SUBSCRIBER_INDEX_GAP",
                    "No subscriber indexed for topic " + ctx.topicShortId()
                            + "; bundle repos not indexed: " + ctx.missingBundleRepos(),
                    ctx));
        }
        return Optional.of(gap(
                "NO_SUBSCRIBER",
                "No subscriber indexed for topic " + ctx.topicShortId(),
                ctx));
    }

    private Optional<MessagingRulePack.TerminalTopicRule> matchTerminalTopic(String topicShortId) {
        if (rules.terminalTopics() == null) {
            return Optional.empty();
        }
        for (MessagingRulePack.TerminalTopicRule rule : rules.terminalTopics()) {
            if (rule.pattern() != null && TopicGlobMatcher.matches(rule.pattern(), topicShortId)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    private static MessagingFlowService.FlowGap gap(
            String code, String detail, TopicContext ctx) {
        return new MessagingFlowService.FlowGap(code, detail, ctx.hopOrder(), ctx.topicShortId());
    }
}
