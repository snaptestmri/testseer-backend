package io.testseer.backend.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class IndexCompleteNotifier {

    private static final Logger log = LoggerFactory.getLogger(IndexCompleteNotifier.class);
    private static final String TOPIC = "testseer-index-complete";

    private final Optional<PubSubTemplate> pubSub;
    private final CacheService cacheService;
    private final ObjectMapper mapper;
    private final IndexNotificationHub notificationHub;
    private final boolean pubSubEnabled;
    private final boolean enrichCommitSha;

    public IndexCompleteNotifier(@Autowired(required = false) PubSubTemplate pubSubTemplate,
                                 CacheService cacheService,
                                 ObjectMapper mapper,
                                 IndexNotificationHub notificationHub,
                                 @Value("${testseer.pubsub.enabled:false}") boolean pubSubEnabled,
                                 @Value("${testseer.notifications.enrich-commit-sha:true}") boolean enrichCommitSha) {
        this.pubSub              = Optional.ofNullable(pubSubTemplate);
        this.cacheService        = cacheService;
        this.mapper              = mapper;
        this.notificationHub     = notificationHub;
        this.pubSubEnabled       = pubSubEnabled;
        this.enrichCommitSha     = enrichCommitSha;
    }

    public void notifyComplete(String orgId, String repo, String serviceId) {
        notifyComplete(orgId, repo, serviceId, null, null);
    }

    public void notifyComplete(String orgId, String repo, String serviceId, String commitSha, String jobId) {
        cacheService.invalidate(orgId, repo, serviceId);
        IndexCompleteEvent event = IndexCompleteEvent.complete(orgId, repo, serviceId, commitSha, jobId);
        notificationHub.publish(event);
        publishPubSub(event);
    }

    public void notifyCleared(String orgId, String repo, String serviceId) {
        cacheService.invalidate(orgId, repo, serviceId);
        IndexCompleteEvent event = IndexCompleteEvent.cleared(orgId, repo, serviceId);
        notificationHub.publish(event);
        publishPubSub(event);
    }

    private void publishPubSub(IndexCompleteEvent event) {
        if (!pubSubEnabled || pubSub.isEmpty()) return;
        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("eventType", event.eventType());
            payload.put("orgId", event.orgId());
            payload.put("repo", event.repo());
            payload.put("serviceId", event.serviceId());
            if (enrichCommitSha && event.commitSha() != null) {
                payload.put("commitSha", event.commitSha());
            }
            if (event.jobId() != null) payload.put("jobId", event.jobId());
            payload.put("indexedAt", event.indexedAt().toString());
            payload.put("scope", event.scope());
            pubSub.get().publish(TOPIC, mapper.writeValueAsBytes(payload));
            log.debug("Published index event {} for {}/{}/{}",
                    event.eventType(), event.orgId(), event.repo(), event.serviceId());
        } catch (Exception ex) {
            log.warn("Failed to publish index-complete event: {}", ex.getMessage());
        }
    }
}
