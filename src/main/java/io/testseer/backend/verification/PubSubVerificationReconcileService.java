package io.testseer.backend.verification;

import io.testseer.backend.query.GcpPubSubVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Reconciles indexed pub/sub facts against each other and optionally live GCP. */
@Service
public class PubSubVerificationReconcileService {

    private static final Logger log = LoggerFactory.getLogger(PubSubVerificationReconcileService.class);

    private final JdbcClient db;
    private final Optional<GcpPubSubVerifier> gcpVerifier;

    public PubSubVerificationReconcileService(
            JdbcClient db,
            @Autowired(required = false) GcpPubSubVerifier gcpVerifier) {
        this.db = db;
        this.gcpVerifier = Optional.ofNullable(gcpVerifier);
    }

    @Scheduled(fixedDelayString = "${testseer.pubsub-verification.reconcile-interval-ms:3600000}")
    public void scheduledReconcile() {
        List<String> orgIds = db.sql("SELECT DISTINCT org_id FROM pubsub_resource_facts")
                .query(String.class)
                .list();
        for (String orgId : orgIds) {
            reconcile(orgId);
        }
    }

    public int reconcile(String orgId) {
        List<PubSubRow> rows = db.sql("""
                SELECT gcp_project, resource_kind, short_id, full_resource_id, env_lane, role
                FROM pubsub_resource_facts
                WHERE org_id = :orgId
                """)
                .param("orgId", orgId)
                .query((rs, row) -> new PubSubRow(
                        rs.getString("gcp_project"),
                        rs.getString("resource_kind"),
                        rs.getString("short_id"),
                        rs.getString("full_resource_id"),
                        rs.getString("env_lane"),
                        rs.getString("role")
                ))
                .list();

        Set<String> topics = new HashSet<>();
        for (PubSubRow row : rows) {
            if ("TOPIC".equals(row.resourceKind()) || "PUBLISH".equals(row.role())) {
                topics.add(topicKey(row.envLane(), row.shortId()));
            }
        }

        boolean liveGcp = gcpVerifier.map(GcpPubSubVerifier::canVerify).orElse(false);
        GcpPubSubVerifier verifier = gcpVerifier.orElse(null);

        int upserts = 0;
        for (PubSubRow row : rows) {
            if (!"SUBSCRIPTION".equals(row.resourceKind()) && !"SUBSCRIBE".equals(row.role())) {
                continue;
            }
            String attachedTopic = inferTopic(row.shortId());
            boolean linked = topics.contains(topicKey(row.envLane(), attachedTopic));
            String drift = linked ? "LINKED_OK" : "MISSING_TOPIC";
            boolean existsInGcp = false;
            String liveAttachedTopic = attachedTopic;

            if (liveGcp && verifier != null && row.fullResourceId() != null && !row.fullResourceId().isBlank()) {
                GcpPubSubVerifier.VerificationResult result = verifier.verifySubscription(
                        row.fullResourceId(), row.shortId(), attachedTopic);
                existsInGcp = result.status() == GcpPubSubVerifier.Status.OK
                        || result.status() == GcpPubSubVerifier.Status.TOPIC_MISMATCH;
                drift = switch (result.status()) {
                    case OK -> linked ? "LINKED_OK" : "MISSING_TOPIC";
                    case SUBSCRIPTION_MISSING -> "GCP_SUB_MISSING";
                    case TOPIC_MISMATCH -> "GCP_TOPIC_MISMATCH";
                    case ERROR -> "VERIFY_ERROR";
                    case SKIPPED -> drift;
                };
                if (result.liveTopicShortId() != null && !result.liveTopicShortId().isBlank()) {
                    liveAttachedTopic = result.liveTopicShortId();
                }
            }

            upserts += db.sql("""
                    INSERT INTO pubsub_verification_facts
                        (gcp_project, resource_kind, short_id, full_resource_id,
                         exists_in_gcp, attached_topic, drift_status)
                    VALUES (:project, :kind, :shortId, :fullId, :exists, :attached, :drift)
                    ON CONFLICT (gcp_project, resource_kind, short_id)
                    DO UPDATE SET
                        full_resource_id = EXCLUDED.full_resource_id,
                        attached_topic = EXCLUDED.attached_topic,
                        drift_status = EXCLUDED.drift_status,
                        exists_in_gcp = EXCLUDED.exists_in_gcp,
                        verified_at = now()
                    """)
                    .param("project", row.gcpProject() != null ? row.gcpProject() : "unknown")
                    .param("kind", row.resourceKind())
                    .param("shortId", row.shortId())
                    .param("fullId", row.fullResourceId())
                    .param("exists", existsInGcp)
                    .param("attached", liveAttachedTopic)
                    .param("drift", drift)
                    .update();
        }
        log.info("Pub/Sub verification reconcile for {}: {} subscription rows (liveGcp={})",
                orgId, upserts, liveGcp);
        return upserts;
    }

    private static String inferTopic(String subscriptionShortId) {
        if (subscriptionShortId == null) return "";
        return subscriptionShortId.replace("_S.", "_T.");
    }

    private static String topicKey(String env, String shortId) {
        return (env != null ? env : "") + "|" + (shortId != null ? shortId : "");
    }

    private record PubSubRow(
            String gcpProject,
            String resourceKind,
            String shortId,
            String fullResourceId,
            String envLane,
            String role
    ) {}
}
