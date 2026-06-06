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
    private final boolean pubSubEnabled;

    public IndexCompleteNotifier(@Autowired(required = false) PubSubTemplate pubSubTemplate,
                               CacheService cacheService,
                               ObjectMapper mapper,
                               @Value("${testseer.pubsub.enabled:false}") boolean pubSubEnabled) {
        this.pubSub         = Optional.ofNullable(pubSubTemplate);
        this.cacheService   = cacheService;
        this.mapper         = mapper;
        this.pubSubEnabled  = pubSubEnabled;
    }

    public void notifyComplete(String orgId, String repo, String serviceId) {
        cacheService.invalidate(orgId, repo, serviceId);

        if (pubSubEnabled && pubSub.isPresent()) {
            try {
                ObjectNode payload = mapper.createObjectNode();
                payload.put("orgId", orgId);
                payload.put("repo", repo);
                payload.put("serviceId", serviceId);
                pubSub.get().publish(TOPIC, mapper.writeValueAsBytes(payload));
                log.debug("Published index-complete for {}/{}/{}", orgId, repo, serviceId);
            } catch (Exception ex) {
                log.warn("Failed to publish index-complete event: {}", ex.getMessage());
            }
        }
    }
}
