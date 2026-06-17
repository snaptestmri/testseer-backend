package io.testseer.backend.query;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CrossRepoFollowPolicyTest {

    @Test
    void shouldExpandFrom_runtimeSkipsManifestYamlWithoutLinkedClass() {
        var policy = policy(CrossRepoFollowPolicy.MODE_RUNTIME, Set.of("platform-argocd-manifest"));
        var manifestSub = subscriber("manifest-svc", "platform-argocd-manifest", null, "YAML");

        assertThat(policy.shouldExpandFrom(manifestSub)).isFalse();
    }

    @Test
    void shouldExpandFrom_inventoryExpandsManifestSubscriber() {
        var policy = policy(CrossRepoFollowPolicy.MODE_INVENTORY, Set.of("platform-argocd-manifest"));
        var manifestSub = subscriber("manifest-svc", "platform-argocd-manifest", null, "YAML");

        assertThat(policy.shouldExpandFrom(manifestSub)).isTrue();
    }

    @Test
    void shouldExpandFrom_runtimeExpandsLinkedClassSubscriber() {
        var policy = policy(CrossRepoFollowPolicy.MODE_RUNTIME, Set.of("platform-argocd-manifest"));
        var runtimeSub = subscriber("eval-svc", "transaction-eval-consumer", "com.example.Handler", "JAVAPARSER");

        assertThat(policy.shouldExpandFrom(runtimeSub)).isTrue();
    }

    @Test
    void shouldExpandFrom_runtimeSkipsManifestEvenWithLinkedClass() {
        var policy = policy(CrossRepoFollowPolicy.MODE_RUNTIME, Set.of("platform-argocd-manifest"));
        var manifestSub = subscriber(
                "manifest-svc", "platform-argocd-manifest", "com.example.TransactionEvalConsumer", "JAVAPARSER");

        assertThat(policy.shouldExpandFrom(manifestSub)).isFalse();
    }

    @Test
    void expandFromSubscribers_countsSkippedManifestInRuntimeMode() {
        var policy = policy(CrossRepoFollowPolicy.MODE_RUNTIME, Set.of("platform-argocd-manifest"));
        var manifestSub = subscriber("manifest-svc", "platform-argocd-manifest", null, "YAML");
        var runtimeSub = subscriber("eval-svc", "transaction-eval-consumer", "com.example.Handler", "JAVAPARSER");
        var pub = publisher("eval-svc", "transaction-eval-consumer", "PDN_T.DOWNSTREAM");

        CrossRepoFollowPolicy.ExpansionResult result = policy.expandFromSubscribers(
                List.of(manifestSub, runtimeSub),
                List.of(manifestSub, runtimeSub, pub),
                KafkaTopicAliasIndex.from(io.testseer.backend.config.MessagingRulePack.empty()),
                Set.of());

        assertThat(result.skippedSubscribers()).isEqualTo(1);
        assertThat(result.topics()).containsExactly("PDN_T.DOWNSTREAM");
    }

    @Test
    void isRuntimePublisher_falseForManifestYamlOnly() {
        var pub = publisher("manifest-svc", "platform-argocd-manifest", "PDN_T.TOPIC");
        pub = new MessagingFlowService.PubSubOrgView(
                pub.serviceId(), pub.repo(), pub.serviceName(), pub.resourceKind(), pub.shortId(),
                pub.envLane(), pub.role(), pub.springKey(), pub.fullResourceId(), pub.moduleName(),
                null, pub.workloadName(), "YAML", pub.confidence(), pub.transport(),
                pub.consistencyHints(), pub.inboundTriggers(), pub.liveVerification());

        assertThat(CrossRepoFollowPolicy.isRuntimePublisher(pub, Set.of("platform-argocd-manifest"))).isFalse();
    }

    private static CrossRepoFollowPolicy policy(String mode, Set<String> manifestRepos) {
        return new CrossRepoFollowPolicy(new CrossRepoFollowPolicy.Context(
                mode, manifestRepos, Set.of(), Map.of(), List.of()));
    }

    private static MessagingFlowService.PubSubOrgView subscriber(
            String serviceId, String repo, String linkedClassFqn, String evidence) {
        return new MessagingFlowService.PubSubOrgView(
                serviceId, repo, serviceId, "SUBSCRIPTION", "PDN_S.TOPIC", "pdn", "SUBSCRIBE",
                null, null, "module", linkedClassFqn, "workload", evidence, 1.0, "PUBSUB",
                List.of(), List.of(), null);
    }

    private static MessagingFlowService.PubSubOrgView publisher(String serviceId, String repo, String topic) {
        return new MessagingFlowService.PubSubOrgView(
                serviceId, repo, serviceId, "TOPIC", topic, "pdn", "PUBLISH",
                null, null, "module", "com.example.Publisher", "workload", "JAVAPARSER", 1.0, "PUBSUB",
                List.of(), List.of(), null);
    }
}
