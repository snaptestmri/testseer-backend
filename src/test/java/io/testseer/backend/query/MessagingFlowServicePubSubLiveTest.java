package io.testseer.backend.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessagingFlowServicePubSubLiveTest {

    @Mock GcpPubSubVerifier gcpVerifier;

    private MessagingFlowService service;

    @BeforeEach
    void setUp() {
        service = new MessagingFlowService(
                null, null, null, null, null, null, null, null, gcpVerifier);
    }

    @Test
    void applyLiveToPubSubViews_enrichesSubscriptionWhenVerifierOk() throws Exception {
        when(gcpVerifier.shouldVerify(true)).thenReturn(true);
        when(gcpVerifier.canVerifyForRequest(true)).thenReturn(true);
        when(gcpVerifier.verifySubscription(
                eq("projects/pdn/subscriptions/PDN_S.FOO"),
                eq("PDN_S.FOO"),
                isNull(),
                eq(true)))
                .thenReturn(new GcpPubSubVerifier.VerificationResult(
                        GcpPubSubVerifier.Status.OK,
                        "PDN_T.FOO",
                        "projects/pdn/topics/PDN_T.FOO",
                        "PDN_T.FOO",
                        Instant.parse("2026-06-15T18:00:00Z"),
                        "GCP_PUBSUB_ADMIN"));

        MessagingFlowService.PubSubView sub = new MessagingFlowService.PubSubView(
                "SUBSCRIPTION", "PDN_S.FOO", "pdn", "SUBSCRIBE",
                null, "projects/pdn/subscriptions/PDN_S.FOO",
                "mod", "com.example.Handler", "wl", "YAML", 1.0, "PUBSUB", null);
        MessagingFlowService.PubSubView topic = new MessagingFlowService.PubSubView(
                "TOPIC", "PDN_T.FOO", "pdn", "PUBLISH",
                null, null, "mod", null, "wl", "YAML", 1.0, "PUBSUB", null);

        MessagingFlowService.PubSubLiveInventoryResult<MessagingFlowService.PubSubView> result =
                invokeApplyLive(List.of(topic, sub), true);

        assertThat(result.summary().status()).isEqualTo("OK");
        assertThat(result.summary().verifiedCount()).isEqualTo(1);
        assertThat(result.items().get(1).liveVerification()).isNotNull();
        assertThat(result.items().get(1).liveVerification().status()).isEqualTo("OK");
        assertThat(result.items().get(0).liveVerification()).isNull();
    }

    @Test
    void applyLiveToPubSubViews_returnsDisabledWhenVerifyOff() throws Exception {
        when(gcpVerifier.shouldVerify(false)).thenReturn(false);

        MessagingFlowService.PubSubView sub = new MessagingFlowService.PubSubView(
                "SUBSCRIPTION", "PDN_S.FOO", "pdn", "SUBSCRIBE",
                null, "projects/pdn/subscriptions/PDN_S.FOO",
                "mod", null, "wl", "YAML", 1.0, "PUBSUB", null);

        MessagingFlowService.PubSubLiveInventoryResult<MessagingFlowService.PubSubView> result =
                invokeApplyLive(List.of(sub), false);

        assertThat(result.summary().status()).isEqualTo("DISABLED");
        assertThat(result.items().get(0).liveVerification()).isNull();
    }

    @SuppressWarnings("unchecked")
    private MessagingFlowService.PubSubLiveInventoryResult<MessagingFlowService.PubSubView> invokeApplyLive(
            List<MessagingFlowService.PubSubView> rows, boolean liveVerify) throws Exception {
        Method method = MessagingFlowService.class.getDeclaredMethod(
                "applyLiveToPubSubViews", List.class, boolean.class);
        method.setAccessible(true);
        return (MessagingFlowService.PubSubLiveInventoryResult<MessagingFlowService.PubSubView>)
                method.invoke(service, rows, liveVerify);
    }
}
