package io.testseer.backend.ingestion.messaging;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.ParsedModel;
import io.testseer.backend.ingestion.graph.MethodCallGraphExtractor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Emits outbound facts for {@code SyncProducer}/{@code AsyncProducer}.send(...) call sites.
 * Complements HTTP-only extraction in {@link io.testseer.backend.ingestion.JavaParserService}.
 */
@Component
public class KafkaPublishOutboundExtractor {

    public List<FactBatch.OutboundCallFact> extract(
            List<ParsedModel> models,
            List<FactBatch.PubSubResourceFact> linkedPubSub) {

        Map<String, ParsedModel.FieldInjectionDef> producerFieldsByClass = indexProducerFields(models);
        List<FactBatch.OutboundCallFact> facts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (ParsedModel model : models) {
            if (model.classFqn() == null) {
                continue;
            }
            for (ParsedModel.MethodCallDef call : model.methodCalls()) {
                if (!"send".equals(call.calleeMethod()) || call.calleeVariable() == null) {
                    continue;
                }
                ParsedModel.FieldInjectionDef producer = producerFieldsByClass.get(
                        model.classFqn() + "|" + call.calleeVariable());
                if (producer == null || !MethodCallGraphExtractor.isKafkaProducerType(producer.declaredType())) {
                    continue;
                }
                String topic = resolveTopic(model.classFqn(), producer, linkedPubSub);
                String sourceSymbol = model.classFqn() + "#" + call.callerMethod();
                String dedupe = sourceSymbol + "|KAFKA|" + (topic != null ? topic : producer.variableName());
                if (!seen.add(dedupe)) {
                    continue;
                }
                facts.add(new FactBatch.OutboundCallFact(
                        sourceSymbol,
                        "KAFKA",
                        topic,
                        "KAFKA_PUBLISH",
                        topic != null ? 0.92 : 0.85));
            }
        }
        return facts;
    }

    private static Map<String, ParsedModel.FieldInjectionDef> indexProducerFields(List<ParsedModel> models) {
        Map<String, ParsedModel.FieldInjectionDef> index = new LinkedHashMap<>();
        for (ParsedModel model : models) {
            if (model.classFqn() == null) {
                continue;
            }
            for (ParsedModel.FieldInjectionDef field : model.fieldInjections()) {
                if (!MethodCallGraphExtractor.isKafkaProducerType(field.declaredType())) {
                    continue;
                }
                index.put(model.classFqn() + "|" + field.variableName(), field);
            }
        }
        return index;
    }

    private static String resolveTopic(
            String classFqn,
            ParsedModel.FieldInjectionDef producer,
            List<FactBatch.PubSubResourceFact> linkedPubSub) {

        String beanKey = producer.beanName();
        if (beanKey == null || beanKey.isBlank()) {
            beanKey = producer.variableName();
        }
        String topicSegment = beanNameToTopicSegment(beanKey);
        String springPrefix = topicSegment != null ? "kafka.topics." + topicSegment : null;

        if (springPrefix != null) {
            for (FactBatch.PubSubResourceFact fact : linkedPubSub) {
                if (!isKafkaPublish(fact)) {
                    continue;
                }
                if (fact.springKey() != null && fact.springKey().startsWith(springPrefix)) {
                    return fact.shortId();
                }
            }
        }

        for (FactBatch.PubSubResourceFact fact : linkedPubSub) {
            if (!isKafkaPublish(fact)) {
                continue;
            }
            if (classFqn.equals(fact.linkedClassFqn())) {
                if (springPrefix == null
                        || (fact.springKey() != null && fact.springKey().startsWith(springPrefix))) {
                    return fact.shortId();
                }
            }
        }
        return null;
    }

    private static boolean isKafkaPublish(FactBatch.PubSubResourceFact fact) {
        return YamlKafkaTopicExtractor.isKafkaFact(fact)
                && "PUBLISH".equalsIgnoreCase(fact.role());
    }

    static String beanNameToTopicSegment(String beanName) {
        if (beanName == null || beanName.isBlank()) {
            return null;
        }
        String stem = beanName.replaceAll("(?i)(Sync|Async)?Producer$", "");
        if (stem.isBlank()) {
            return null;
        }
        return camelCaseToDotPath(stem);
    }

    private static String camelCaseToDotPath(String camel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) {
                sb.append('.');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
