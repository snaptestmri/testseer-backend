package io.testseer.backend.ingestion.triggers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.messaging.YamlKafkaTopicExtractor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class PubSubSubscribeTriggerExtractor {

    private final ObjectMapper mapper = new ObjectMapper();

    public List<FactBatch.EntryTriggerFact> extract(
            List<FactBatch.PubSubResourceFact> pubsubFacts, String defaultEnvLane) {
        List<FactBatch.EntryTriggerFact> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (pubsubFacts == null) return results;

        for (FactBatch.PubSubResourceFact fact : pubsubFacts) {
            if (!"SUBSCRIBE".equals(fact.role())) continue;
            if (YamlKafkaTopicExtractor.isKafkaFact(fact)) continue;
            String triggerId = "pubsub:" + sanitize(fact.shortId()) + ":" + sanitize(fact.linkedClassFqn());
            if (!seen.add(triggerId)) continue;

            results.add(new FactBatch.EntryTriggerFact(
                    triggerId,
                    "PUBSUB_SUBSCRIBE",
                    "INBOUND",
                    fact.envLane() != null ? fact.envLane() : defaultEnvLane,
                    "pubsub",
                    "INTERNAL",
                    null,
                    fact.shortId(),
                    fact.linkedClassFqn(),
                    null,
                    null,
                    fact.springKey(),
                    "PUBSUB_LINK",
                    fact.confidence(),
                    attributes(fact)
            ));
        }
        return results;
    }

    private String attributes(FactBatch.PubSubResourceFact fact) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "shortId", fact.shortId(),
                    "fullResourceId", fact.fullResourceId() != null ? fact.fullResourceId() : "",
                    "workloadName", fact.workloadName() != null ? fact.workloadName() : ""
            ));
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replaceAll("[^a-zA-Z0-9._-]+", "-").toLowerCase(Locale.ROOT);
    }
}
