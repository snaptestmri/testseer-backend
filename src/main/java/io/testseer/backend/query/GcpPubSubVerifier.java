package io.testseer.backend.query;

import com.google.api.gax.rpc.NotFoundException;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.cloud.spring.pubsub.PubSubAdmin;
import com.google.pubsub.v1.Subscription;
import io.testseer.backend.config.MessagingRulePack;
import io.testseer.backend.config.MessagingRulePackLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional live-verification for GCP Pub/Sub subscriptions (MSG-10).
 *
 * <p>Uses {@link SubscriptionAdminClient} for cross-project lookups when
 * {@code full_resource_id} is a full resource name ({@code projects/.../subscriptions/...}).
 * Falls back to Spring {@link PubSubAdmin} for short subscription ids on the default project.
 */
@Component
public class GcpPubSubVerifier {

    private static final Logger log = LoggerFactory.getLogger(GcpPubSubVerifier.class);
    private static final String EVIDENCE = "GCP_PUBSUB_ADMIN";

    private final PubSubAdmin pubSubAdmin;
    private final String defaultProjectId;
    private final boolean liveVerifyEnabled;
    private final MessagingRulePackLoader rulePackLoader;
    private final Map<String, VerificationResult> cache = new ConcurrentHashMap<>();
    private volatile SubscriptionAdminClient subscriptionAdminClient;
    private volatile boolean subscriptionAdminClientUnavailable;

    public GcpPubSubVerifier(
            @Autowired(required = false) PubSubAdmin pubSubAdmin,
            @Value("${spring.cloud.gcp.pubsub.project-id:}") String defaultProjectId,
            @Value("${testseer.pubsub.live-verify-enabled:false}") boolean liveVerifyEnabled,
            MessagingRulePackLoader rulePackLoader) {
        this.pubSubAdmin = pubSubAdmin;
        this.defaultProjectId = defaultProjectId;
        this.liveVerifyEnabled = liveVerifyEnabled;
        this.rulePackLoader = rulePackLoader;
    }

    /** @deprecated use {@link VerificationResult#status()} */
    @Deprecated
    public enum Result {
        OK,
        MISSING,
        SKIPPED,
        ERROR
    }

    public enum Status {
        OK,
        SUBSCRIPTION_MISSING,
        TOPIC_MISMATCH,
        SKIPPED,
        ERROR
    }

    public record VerificationResult(
            Status status,
            String expectedTopicShortId,
            String liveTopicFullName,
            String liveTopicShortId,
            Instant verifiedAt,
            String evidenceSource
    ) {
        public static VerificationResult skipped() {
            return new VerificationResult(Status.SKIPPED, null, null, null, null, null);
        }
    }

    public boolean isLiveVerifyEnabled() {
        return liveVerifyEnabled;
    }

    /** Reconcile / scheduled jobs — requires global flag. */
    public boolean canVerify() {
        return liveVerifyEnabled && hasAdminClient();
    }

    /** Query-time opt-in via {@code ?liveVerify=true} or global flag. */
    public boolean canVerifyForRequest(boolean liveVerifyParam) {
        if (!shouldVerify(liveVerifyParam)) {
            return false;
        }
        return hasAdminClient() || liveVerifyParam || liveVerifyEnabled;
    }

    public boolean shouldVerify(boolean liveVerifyParam) {
        return liveVerifyParam || liveVerifyEnabled;
    }

    public VerificationResult verifySubscription(
            String fullResourceId,
            String subscriptionShortId,
            String hopTopicShortId) {
        return verifySubscription(fullResourceId, subscriptionShortId, hopTopicShortId, liveVerifyEnabled);
    }

    public VerificationResult verifySubscription(
            String fullResourceId,
            String subscriptionShortId,
            String hopTopicShortId,
            boolean liveVerifyParam) {
        if (!canVerifyForRequest(liveVerifyParam)) {
            return VerificationResult.skipped();
        }
        if (fullResourceId == null || fullResourceId.isBlank()) {
            return VerificationResult.skipped();
        }
        if (!resolveAdminForLookup()) {
            return VerificationResult.skipped();
        }

        String expectedTopic = resolveExpectedTopic(subscriptionShortId, hopTopicShortId);
        String cacheKey = fullResourceId + "|" + expectedTopic;
        VerificationResult cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String subscriptionResourceName = resolveSubscriptionResourceName(fullResourceId, defaultProjectId);
        VerificationResult result = verifySubscriptionInternal(
                subscriptionResourceName, fullResourceId, expectedTopic);
        cache.put(cacheKey, result);
        return result;
    }

    /** @deprecated use {@link VerificationResult#status()} */
    @Deprecated
    public Result verifyByFullResourceId(String fullResourceId) {
        VerificationResult vr = verifySubscription(fullResourceId, null, null);
        return switch (vr.status()) {
            case OK -> Result.OK;
            case SUBSCRIPTION_MISSING -> Result.MISSING;
            case SKIPPED -> Result.SKIPPED;
            default -> Result.ERROR;
        };
    }

    public String resolveExpectedTopic(String subscriptionShortId, String hopTopicShortId) {
        if (subscriptionShortId != null && !subscriptionShortId.isBlank()) {
            MessagingRulePack.SubscriptionTopicRule rule =
                    rulePackLoader.getRulePack().subscriptionTopicMap().get(subscriptionShortId);
            if (rule != null && rule.topicShortId() != null && !rule.topicShortId().isBlank()) {
                return rule.topicShortId();
            }
        }
        if (hopTopicShortId != null && !hopTopicShortId.isBlank()) {
            return hopTopicShortId;
        }
        if (subscriptionShortId != null) {
            return subscriptionShortId.replace("_S.", "_T.");
        }
        return "";
    }

    static String resolveSubscriptionResourceName(String fullResourceId, String defaultProjectId) {
        if (fullResourceId == null || fullResourceId.isBlank()) {
            return "";
        }
        if (fullResourceId.contains("/subscriptions/")) {
            return fullResourceId;
        }
        if (defaultProjectId != null && !defaultProjectId.isBlank()) {
            return "projects/" + defaultProjectId + "/subscriptions/" + fullResourceId;
        }
        return fullResourceId;
    }

    static String extractProjectFromResourceName(String resourceName) {
        if (resourceName == null || !resourceName.startsWith("projects/")) {
            return "";
        }
        int end = resourceName.indexOf('/', "projects/".length());
        return end > 0 ? resourceName.substring("projects/".length(), end) : "";
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private boolean hasAdminClient() {
        return pubSubAdmin != null || subscriptionAdminClient != null;
    }

    private boolean resolveAdminForLookup() {
        if (pubSubAdmin != null || subscriptionAdminClient != null) {
            return true;
        }
        if (subscriptionAdminClientUnavailable) {
            return false;
        }
        synchronized (this) {
            if (subscriptionAdminClient != null) {
                return true;
            }
            if (subscriptionAdminClientUnavailable) {
                return false;
            }
            try {
                subscriptionAdminClient = SubscriptionAdminClient.create();
                return true;
            } catch (IOException ex) {
                subscriptionAdminClientUnavailable = true;
                log.warn("GcpPubSubVerifier: SubscriptionAdminClient unavailable: {}", ex.getMessage());
                return pubSubAdmin != null;
            }
        }
    }

    private Subscription fetchSubscription(String subscriptionResourceName) {
        SubscriptionAdminClient client = subscriptionAdminClient;
        if (client != null) {
            try {
                return client.getSubscription(subscriptionResourceName);
            } catch (NotFoundException ex) {
                return null;
            }
        }
        if (pubSubAdmin != null) {
            return pubSubAdmin.getSubscription(subscriptionResourceName);
        }
        return null;
    }

    private VerificationResult verifySubscriptionInternal(
            String subscriptionResourceName, String logLabel, String expectedTopicShortId) {
        try {
            Subscription sub = fetchSubscription(subscriptionResourceName);
            if (sub == null) {
                log.debug("GCP subscription not found: {}", logLabel);
                return new VerificationResult(
                        Status.SUBSCRIPTION_MISSING,
                        expectedTopicShortId, null, null, Instant.now(), EVIDENCE);
            }

            String liveTopicFull = sub.getTopic();
            String liveTopicShort = extractTopicShortId(liveTopicFull);
            if (expectedTopicShortId != null
                    && !expectedTopicShortId.isBlank()
                    && !topicsMatch(expectedTopicShortId, liveTopicShort, liveTopicFull)) {
                log.debug("GCP topic mismatch for {}: expected={}, live={}",
                        logLabel, expectedTopicShortId, liveTopicShort);
                return new VerificationResult(
                        Status.TOPIC_MISMATCH,
                        expectedTopicShortId, liveTopicFull, liveTopicShort, Instant.now(), EVIDENCE);
            }

            log.trace("GCP subscription verified: {}", logLabel);
            return new VerificationResult(
                    Status.OK,
                    expectedTopicShortId, liveTopicFull, liveTopicShort, Instant.now(), EVIDENCE);
        } catch (Exception ex) {
            log.warn("GcpPubSubVerifier: error checking subscription '{}': {}",
                    logLabel, ex.getMessage());
            return new VerificationResult(
                    Status.ERROR, expectedTopicShortId, null, null, Instant.now(), EVIDENCE);
        }
    }

    static String extractSubscriptionName(String fullResourceId) {
        if (fullResourceId == null) return "";
        int idx = fullResourceId.lastIndexOf('/');
        if (idx >= 0 && idx < fullResourceId.length() - 1) {
            return fullResourceId.substring(idx + 1);
        }
        return fullResourceId;
    }

    static String extractTopicShortId(String topicFullName) {
        if (topicFullName == null || topicFullName.isBlank()) {
            return "";
        }
        int idx = topicFullName.lastIndexOf('/');
        if (idx >= 0 && idx < topicFullName.length() - 1) {
            return topicFullName.substring(idx + 1);
        }
        return topicFullName;
    }

    static boolean topicsMatch(String expectedShortId, String liveShortId, String liveFullName) {
        if (expectedShortId == null || expectedShortId.isBlank()) {
            return true;
        }
        if (expectedShortId.equals(liveShortId)) {
            return true;
        }
        if (liveFullName != null && liveFullName.endsWith("/" + expectedShortId)) {
            return true;
        }
        return normalizeTopicId(expectedShortId).equals(normalizeTopicId(liveShortId));
    }

    private static String normalizeTopicId(String id) {
        if (id == null) return "";
        return id.replace("_S.", "_T.").toLowerCase();
    }
}
