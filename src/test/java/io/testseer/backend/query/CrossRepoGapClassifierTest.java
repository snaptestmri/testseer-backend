package io.testseer.backend.query;

import io.testseer.backend.config.MessagingRulePack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CrossRepoGapClassifierTest {

    private static final Set<String> MANIFEST_REPOS = Set.of("platform-argocd-manifest");

    @Test
    void classifyMissingSubscriber_terminalExternalForAstraPattern() {
        var rules = new MessagingRulePack.CrossRepoTraceRule(
                List.copyOf(MANIFEST_REPOS),
                List.of(),
                List.of(new MessagingRulePack.TerminalTopicRule(
                        "astra", "*.ASTRA", "EXTERNAL", "Partner ASTRA boundary")));
        var classifier = new CrossRepoGapClassifier(rules, MANIFEST_REPOS);

        var gap = classifier.classifyMissingSubscriber(new CrossRepoGapClassifier.TopicContext(
                "PDN_T.OFFER.ASTRA",
                2,
                List.of(runtimePublisher("svc-pub", "orders-repo")),
                List.of(),
                List.of()));

        assertThat(gap).isPresent();
        assertThat(gap.get().gapType()).isEqualTo("TERMINAL_EXTERNAL");
        assertThat(gap.get().topicShortId()).isEqualTo("PDN_T.OFFER.ASTRA");
    }

    @Test
    void classifyMissingSubscriber_manifestOnlyPublisher() {
        var classifier = new CrossRepoGapClassifier(MessagingRulePack.CrossRepoTraceRule.empty(), MANIFEST_REPOS);

        var gap = classifier.classifyMissingSubscriber(new CrossRepoGapClassifier.TopicContext(
                "PDN_T.MANIFEST_TOPIC",
                1,
                List.of(manifestPublisher("manifest-svc", "platform-argocd-manifest")),
                List.of(),
                List.of()));

        assertThat(gap).isPresent();
        assertThat(gap.get().gapType()).isEqualTo("MANIFEST_ONLY_PUBLISHER");
    }

    @Test
    void classifyMissingSubscriber_noSubscriberForRuntimePublisher() {
        var classifier = new CrossRepoGapClassifier(MessagingRulePack.CrossRepoTraceRule.empty(), MANIFEST_REPOS);

        var gap = classifier.classifyMissingSubscriber(new CrossRepoGapClassifier.TopicContext(
                "PDN_T.RUNTIME_TOPIC",
                1,
                List.of(runtimePublisher("svc-pub", "orders-repo")),
                List.of(),
                List.of()));

        assertThat(gap).isPresent();
        assertThat(gap.get().gapType()).isEqualTo("NO_SUBSCRIBER");
    }

    @Test
    void classifyMissingSubscriber_emptyWhenSubscribersPresent() {
        var classifier = new CrossRepoGapClassifier(MessagingRulePack.CrossRepoTraceRule.empty(), MANIFEST_REPOS);

        var gap = classifier.classifyMissingSubscriber(new CrossRepoGapClassifier.TopicContext(
                "PDN_T.TOPIC",
                1,
                List.of(runtimePublisher("svc-pub", "orders-repo")),
                List.of(runtimeSubscriber("svc-sub", "fulfillment-repo")),
                List.of()));

        assertThat(gap).isEmpty();
    }

    private static MessagingFlowService.PubSubOrgView runtimePublisher(String serviceId, String repo) {
        return pubSub(serviceId, repo, "PUBLISH", "com.example.Publisher", "JAVAPARSER");
    }

    private static MessagingFlowService.PubSubOrgView runtimeSubscriber(String serviceId, String repo) {
        return pubSub(serviceId, repo, "SUBSCRIBE", "com.example.Consumer", "JAVAPARSER");
    }

    private static MessagingFlowService.PubSubOrgView manifestPublisher(String serviceId, String repo) {
        return pubSub(serviceId, repo, "PUBLISH", null, "YAML");
    }

    private static MessagingFlowService.PubSubOrgView pubSub(
            String serviceId, String repo, String role, String linkedClassFqn, String evidence) {
        return new MessagingFlowService.PubSubOrgView(
                serviceId, repo, serviceId, "TOPIC", "PDN_T.TOPIC", "pdn", role,
                null, null, "module", linkedClassFqn, "workload", evidence, 1.0, "PUBSUB",
                List.of(), List.of(), null);
    }
}
