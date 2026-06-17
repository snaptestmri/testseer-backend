package io.testseer.backend.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import com.google.cloud.spring.pubsub.support.BasicAcknowledgeablePubsubMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "testseer.pubsub.enabled", havingValue = "true")
public class CacheInvalidationListener {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationListener.class);
    private static final String SUBSCRIPTION = "testseer-index-complete-sub";

    private final PubSubTemplate pubSub;
    private final CacheService cacheService;
    private final ObjectMapper mapper;

    public CacheInvalidationListener(PubSubTemplate pubSub,
                                     CacheService cacheService,
                                     ObjectMapper mapper) {
        this.pubSub       = pubSub;
        this.cacheService = cacheService;
        this.mapper       = mapper;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void subscribe() {
        pubSub.subscribe(SUBSCRIPTION, this::handleMessage);
        log.info("Subscribed to Pub/Sub {}", SUBSCRIPTION);
    }

    private void handleMessage(BasicAcknowledgeablePubsubMessage message) {
        try {
            String payload  = message.getPubsubMessage().getData().toStringUtf8();
            JsonNode node   = mapper.readTree(payload);
            String orgId    = node.path("orgId").asText();
            String repo     = node.path("repo").asText();
            String serviceId = node.path("serviceId").asText();

            cacheService.invalidate(orgId, repo, serviceId);
            log.info("Cache invalidated for {}/{}/{} via Pub/Sub (eventType={})",
                    orgId, repo, serviceId, node.path("eventType").asText("INDEX_COMPLETE"));
            message.ack();
        } catch (Exception ex) {
            log.error("Failed to process Pub/Sub invalidation message: {}", ex.getMessage());
            message.nack();
        }
    }
}
