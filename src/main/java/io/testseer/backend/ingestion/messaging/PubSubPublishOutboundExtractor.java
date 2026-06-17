package io.testseer.backend.ingestion.messaging;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.ParsedModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Emits outbound facts for {@code PubSubMsgGateway.sendByteArrayToPubSub(topic, bytes)} call sites.
 */
@Component
public class PubSubPublishOutboundExtractor {

    private static final Pattern TOPIC_FIELD_GET =
            Pattern.compile("getTopicId\\(\\)\\.get\\(\"([^\"]+)\"\\)");
    private static final Pattern TOPIC_ASSIGN_GET =
            Pattern.compile("\\.get\\(\"([^\"]+)\"\\)");

    public List<FactBatch.OutboundCallFact> extract(
            List<ParsedModel> models,
            List<FactBatch.PubSubResourceFact> linkedPubSub,
            Map<String, String> sourceByClassFqn) {

        Map<String, ParsedModel.FieldInjectionDef> gatewayFields = indexGatewayFields(models);
        List<FactBatch.OutboundCallFact> facts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (ParsedModel model : models) {
            if (model.classFqn() == null) {
                continue;
            }
            String source = sourceByClassFqn != null ? sourceByClassFqn.get(model.classFqn()) : null;
            for (ParsedModel.MethodCallDef call : model.methodCalls()) {
                if (!"sendByteArrayToPubSub".equals(call.calleeMethod())) {
                    continue;
                }
                String gatewayVar = call.calleeVariable();
                if (gatewayVar == null) {
                    continue;
                }
                ParsedModel.FieldInjectionDef gateway = gatewayFields.get(
                        model.classFqn() + "|" + gatewayVar);
                if (gateway == null && !isGatewayVariable(gatewayVar)) {
                    continue;
                }
                String topic = resolveTopic(model.classFqn(), source, linkedPubSub);
                String sourceSymbol = model.classFqn() + "#" + call.callerMethod();
                String dedupe = sourceSymbol + "|PUBSUB|" + (topic != null ? topic : gatewayVar);
                if (!seen.add(dedupe)) {
                    continue;
                }
                facts.add(new FactBatch.OutboundCallFact(
                        sourceSymbol,
                        "PUBSUB",
                        topic,
                        "PUBSUB_PUBLISH",
                        topic != null ? 0.92 : 0.85));
            }
        }
        return facts;
    }

    private static Map<String, ParsedModel.FieldInjectionDef> indexGatewayFields(List<ParsedModel> models) {
        Map<String, ParsedModel.FieldInjectionDef> index = new LinkedHashMap<>();
        for (ParsedModel model : models) {
            if (model.classFqn() == null) {
                continue;
            }
            for (ParsedModel.FieldInjectionDef field : model.fieldInjections()) {
                if (isGatewayType(field.declaredType()) || isGatewayVariable(field.variableName())) {
                    index.put(model.classFqn() + "|" + field.variableName(), field);
                }
            }
        }
        return index;
    }

    private static boolean isGatewayType(String type) {
        return type != null && type.contains("PubSubMsgGateway");
    }

    private static boolean isGatewayVariable(String name) {
        return name != null && name.toLowerCase().contains("publishgateway");
    }

    private static String resolveTopic(
            String classFqn,
            String source,
            List<FactBatch.PubSubResourceFact> linkedPubSub) {

        for (FactBatch.PubSubResourceFact fact : linkedPubSub) {
            if (!"PUBLISH".equalsIgnoreCase(fact.role())) {
                continue;
            }
            if (classFqn.equals(fact.linkedClassFqn())) {
                return fact.shortId();
            }
        }

        String springLeaf = inferSpringLeaf(source);
        if (springLeaf == null) {
            return null;
        }
        String springPrefix = "pubsub.publisher.topicId." + springLeaf;
        for (FactBatch.PubSubResourceFact fact : linkedPubSub) {
            if (!"PUBLISH".equalsIgnoreCase(fact.role())) {
                continue;
            }
            if (fact.springKey() != null && fact.springKey().startsWith(springPrefix)) {
                return fact.shortId();
            }
        }
        return null;
    }

    static String inferSpringLeaf(String source) {
        if (source == null) {
            return null;
        }
        Matcher topicField = TOPIC_FIELD_GET.matcher(source);
        if (topicField.find()) {
            return topicField.group(1);
        }
        if (source.contains("freedomumo")) {
            return "freedomumo";
        }
        if (source.contains("partneradapter")) {
            return "partneradapter";
        }
        return null;
    }
}
