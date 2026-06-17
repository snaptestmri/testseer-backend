package io.testseer.backend.query;

import com.google.cloud.spring.pubsub.PubSubAdmin;
import com.google.pubsub.v1.Subscription;
import io.testseer.backend.config.MessagingRulePack;
import io.testseer.backend.config.MessagingRulePackLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GcpPubSubVerifierTest {

    @Mock PubSubAdmin pubSubAdmin;
    @Mock MessagingRulePackLoader rulePackLoader;

    private GcpPubSubVerifier verifier;

    @BeforeEach
    void setUp() {
        lenient().when(rulePackLoader.getRulePack()).thenReturn(rulePackWithSubscriptionMap());
        verifier = new GcpPubSubVerifier(pubSubAdmin, "pdn", true, rulePackLoader);
    }

    private static MessagingRulePack rulePackWithSubscriptionMap() {
        return new MessagingRulePack(
                List.of(), List.of(), Map.of(), List.of(), List.of(), List.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), List.of(),
                Map.of("PDN_S.OFFER_UPDATE.PARTNER_NOTIFY",
                        new MessagingRulePack.SubscriptionTopicRule("PDN_T.OFFER_UPDATE")),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                MessagingRulePack.CrossRepoTraceRule.empty());
    }

    @Test
    void verifySubscription_returnsOkWhenTopicMatches() {
        when(pubSubAdmin.getSubscription("projects/pdn/subscriptions/PDN_S.RIQ_OFFER_EVENT"))
                .thenReturn(Subscription.newBuilder()
                        .setTopic("projects/pdn/topics/PDN_T.RIQ_OFFER_EVENT")
                        .build());

        GcpPubSubVerifier.VerificationResult result = verifier.verifySubscription(
                "projects/pdn/subscriptions/PDN_S.RIQ_OFFER_EVENT",
                "PDN_S.RIQ_OFFER_EVENT",
                "PDN_T.RIQ_OFFER_EVENT");

        assertThat(result.status()).isEqualTo(GcpPubSubVerifier.Status.OK);
        assertThat(result.liveTopicShortId()).isEqualTo("PDN_T.RIQ_OFFER_EVENT");
    }

    @Test
    void verifySubscription_returnsMissingWhenNotInGcp() {
        when(pubSubAdmin.getSubscription(anyString())).thenReturn(null);

        GcpPubSubVerifier.VerificationResult result = verifier.verifySubscription(
                "projects/pdn/subscriptions/missing-sub",
                "missing-sub",
                "PDN_T.RIQ_OFFER_EVENT");

        assertThat(result.status()).isEqualTo(GcpPubSubVerifier.Status.SUBSCRIPTION_MISSING);
    }

    @Test
    void verifySubscription_returnsTopicMismatch() {
        when(pubSubAdmin.getSubscription("projects/pdn/subscriptions/PDN_S.OFFER_UPDATE.PARTNER_NOTIFY"))
                .thenReturn(Subscription.newBuilder()
                        .setTopic("projects/pdn/topics/PDN_T.WRONG_TOPIC")
                        .build());

        GcpPubSubVerifier.VerificationResult result = verifier.verifySubscription(
                "projects/pdn/subscriptions/PDN_S.OFFER_UPDATE.PARTNER_NOTIFY",
                "PDN_S.OFFER_UPDATE.PARTNER_NOTIFY",
                "PDN_T.OFFER_UPDATE");

        assertThat(result.status()).isEqualTo(GcpPubSubVerifier.Status.TOPIC_MISMATCH);
        assertThat(result.expectedTopicShortId()).isEqualTo("PDN_T.OFFER_UPDATE");
        assertThat(result.liveTopicShortId()).isEqualTo("PDN_T.WRONG_TOPIC");
    }

    @Test
    void resolveExpectedTopic_usesRulePackOverride() {
        assertThat(verifier.resolveExpectedTopic("PDN_S.OFFER_UPDATE.PARTNER_NOTIFY", "other"))
                .isEqualTo("PDN_T.OFFER_UPDATE");
    }

    @Test
    void resolveExpectedTopic_fallsBackToHopTopic() {
        assertThat(verifier.resolveExpectedTopic("PDN_S.CUSTOM", "PDN_T.CUSTOM"))
                .isEqualTo("PDN_T.CUSTOM");
    }

    @Test
    void verifySubscription_skippedWhenDisabled() {
        GcpPubSubVerifier disabled = new GcpPubSubVerifier(pubSubAdmin, "pdn", false, rulePackLoader);
        GcpPubSubVerifier.VerificationResult result = disabled.verifySubscription(
                "projects/pdn/subscriptions/x", "x", "PDN_T.X", false);
        assertThat(result.status()).isEqualTo(GcpPubSubVerifier.Status.SKIPPED);
    }

    @Test
    void resolveSubscriptionResourceName_usesFullPathWhenPresent() {
        assertThat(GcpPubSubVerifier.resolveSubscriptionResourceName(
                "projects/other-proj/subscriptions/PDN_S.FOO", "default-proj"))
                .isEqualTo("projects/other-proj/subscriptions/PDN_S.FOO");
    }

    @Test
    void resolveSubscriptionResourceName_buildsFromDefaultProject() {
        assertThat(GcpPubSubVerifier.resolveSubscriptionResourceName("PDN_S.FOO", "pdn"))
                .isEqualTo("projects/pdn/subscriptions/PDN_S.FOO");
    }

    @Test
    void extractTopicShortId_parsesFullResourceName() {
        assertThat(GcpPubSubVerifier.extractTopicShortId("projects/pdn/topics/PDN_T.FOO"))
                .isEqualTo("PDN_T.FOO");
    }
}
