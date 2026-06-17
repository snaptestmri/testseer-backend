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
public class KafkaSubscribeTriggerExtractor {

    private final ObjectMapper mapper = new ObjectMapper();

    public List<FactBatch.EntryTriggerFact> extract(
            List<FactBatch.PubSubResourceFact> messagingFacts, String defaultEnvLane) {
        List<FactBatch.EntryTriggerFact> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (messagingFacts == null) return results;

        for (FactBatch.PubSubResourceFact fact : messagingFacts) {
            if (!YamlKafkaTopicExtractor.isKafkaFact(fact)) continue;
            if (!"SUBSCRIBE".equals(fact.role())) continue;
            if (fact.linkedClassFqn() == null || fact.linkedClassFqn().isBlank()) continue;

            String triggerId = "kafka:" + sanitize(fact.shortId()) + ":" + sanitize(fact.linkedClassFqn());
            if (!seen.add(triggerId)) continue;

            String method = fact.linkedMethod() != null ? fact.linkedMethod() : "onMessage";
            results.add(new FactBatch.EntryTriggerFact(
                    triggerId,
                    "KAFKA_SUBSCRIBE",
                    "INBOUND",
                    fact.envLane() != null ? fact.envLane() : defaultEnvLane,
                    "kafka",
                    "INTERNAL",
                    null,
                    fact.shortId(),
                    fact.linkedClassFqn(),
                    method,
                    null,
                    fact.springKey(),
                    "KAFKA_LINK",
                    fact.confidence(),
                    attributes(fact, method)
            ));
        }
        return results;
    }

    private String attributes(FactBatch.PubSubResourceFact fact, String method) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "shortId", fact.shortId(),
                    "method", method,
                    "transport", "KAFKA",
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
