package io.testseer.backend.query;

import io.testseer.backend.query.MessagingFlowService.PubSubOrgView;
import io.testseer.backend.query.MessagingFlowService.PubSubView;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Resolves PUBSUB vs KAFKA vs HTTP_PUBSUB from pubsub_resource_facts.attributes JSON. */
final class MessagingTransportUtil {

    private static final String DEFAULT = "PUBSUB";
    private static final Pattern TRANSPORT_JSON =
            Pattern.compile("\"transport\"\\s*:\\s*\"([^\"]+)\"");

    private MessagingTransportUtil() {}

    static String fromAttributes(String attributesJson) {
        if (attributesJson == null || attributesJson.isBlank()) {
            return DEFAULT;
        }
        var matcher = TRANSPORT_JSON.matcher(attributesJson);
        if (matcher.find()) {
            String transport = matcher.group(1);
            if (transport != null && !transport.isBlank()) {
                return transport;
            }
        }
        if (attributesJson.contains("HTTP_PUBSUB")) {
            return "HTTP_PUBSUB";
        }
        if (attributesJson.contains("\"KAFKA\"")) {
            return "KAFKA";
        }
        return DEFAULT;
    }

    static String resolveHopTransport(List<PubSubOrgView> participants) {
        return resolveFromTransports(collectTransports(participants));
    }

    static String resolveHopTransportFromViews(List<PubSubView> participants) {
        return resolveFromTransports(collectViewTransports(participants));
    }

    private static Set<String> collectTransports(List<PubSubOrgView> participants) {
        Set<String> transports = new LinkedHashSet<>();
        if (participants == null) {
            return transports;
        }
        for (PubSubOrgView participant : participants) {
            if (participant == null) continue;
            String transport = participant.transport();
            if (transport != null && !transport.isBlank()) {
                transports.add(transport);
            }
        }
        return transports;
    }

    private static Set<String> collectViewTransports(List<PubSubView> participants) {
        Set<String> transports = new LinkedHashSet<>();
        if (participants == null) {
            return transports;
        }
        for (PubSubView participant : participants) {
            if (participant == null) continue;
            String transport = participant.transport();
            if (transport != null && !transport.isBlank()) {
                transports.add(transport);
            }
        }
        return transports;
    }

    private static String resolveFromTransports(Set<String> transports) {
        if (transports.isEmpty()) {
            return DEFAULT;
        }
        if (transports.size() == 1) {
            return transports.iterator().next();
        }
        if (transports.contains("HTTP_PUBSUB")) {
            return "HTTP_PUBSUB";
        }
        if (transports.contains("KAFKA")) {
            return "KAFKA";
        }
        return DEFAULT;
    }
}
