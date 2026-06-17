package io.testseer.backend.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-processes cross-repo trace results for human-readable API output:
 * dedupes hop participants, attaches hop context to gaps, and builds summaries.
 */
final class CrossRepoFlowPresenter {

    private static final Pattern TOPIC_IN_DESCRIPTION =
            Pattern.compile("(?:topic|Topic)\\s+([A-Z0-9_.]+)");

    private CrossRepoFlowPresenter() {}

    record Result(
            List<MessagingFlowService.CrossRepoHop> hops,
            List<MessagingFlowService.FlowGap> gaps,
            List<MessagingFlowService.CrossRepoHopSummary> hopSummaries,
            List<String> narrative,
            List<String> traceWarnings,
            Integer skippedExpansionCount) {}

    static Result present(
            String startTopic,
            List<MessagingFlowService.CrossRepoHop> hops,
            List<MessagingFlowService.FlowGap> gaps) {
        return present(startTopic, hops, gaps, null, List.of(), 0);
    }

    static Result present(
            String startTopic,
            List<MessagingFlowService.CrossRepoHop> hops,
            List<MessagingFlowService.FlowGap> gaps,
            String followMode,
            List<String> traceWarnings,
            int skippedExpansionCount) {
        List<MessagingFlowService.CrossRepoHop> dedupedHops = dedupeHops(hops);
        List<MessagingFlowService.FlowGap> enrichedGaps = enrichGaps(gaps, dedupedHops);
        List<MessagingFlowService.CrossRepoHopSummary> summaries =
                buildHopSummaries(dedupedHops);
        List<String> narrative = buildNarrative(
                startTopic, summaries, enrichedGaps, followMode, traceWarnings, skippedExpansionCount);
        return new Result(
                dedupedHops, enrichedGaps, summaries, narrative,
                traceWarnings != null ? List.copyOf(traceWarnings) : List.of(),
                skippedExpansionCount);
    }

    private static List<MessagingFlowService.CrossRepoHop> dedupeHops(
            List<MessagingFlowService.CrossRepoHop> hops) {
        if (hops == null || hops.isEmpty()) {
            return List.of();
        }
        List<MessagingFlowService.CrossRepoHop> result = new ArrayList<>(hops.size());
        for (MessagingFlowService.CrossRepoHop hop : hops) {
            List<MessagingFlowService.PubSubOrgView> publishers =
                    dedupeParticipants(hop.publishers());
            List<MessagingFlowService.PubSubOrgView> subscribers =
                    dedupeParticipants(hop.subscribers());
            result.add(new MessagingFlowService.CrossRepoHop(
                    hop.order(), hop.topicShortId(), hop.transport(),
                    publishers, subscribers, hop.terminalContinuations()));
        }
        return result;
    }

    static List<MessagingFlowService.PubSubOrgView> dedupeParticipants(
            List<MessagingFlowService.PubSubOrgView> participants) {
        if (participants == null || participants.isEmpty()) {
            return List.of();
        }
        Map<String, MessagingFlowService.PubSubOrgView> byKey = new LinkedHashMap<>();
        for (MessagingFlowService.PubSubOrgView row : participants) {
            String key = dedupeKey(row);
            MessagingFlowService.PubSubOrgView existing = byKey.get(key);
            if (existing == null || row.confidence() > existing.confidence()) {
                byKey.put(key, row);
            }
        }
        return List.copyOf(byKey.values());
    }

    private static String dedupeKey(MessagingFlowService.PubSubOrgView row) {
        return nullToEmpty(row.serviceId()) + "|" + nullToEmpty(row.role());
    }

    private static List<MessagingFlowService.FlowGap> enrichGaps(
            List<MessagingFlowService.FlowGap> gaps,
            List<MessagingFlowService.CrossRepoHop> hops) {
        if (gaps == null || gaps.isEmpty()) {
            return List.of();
        }
        List<MessagingFlowService.FlowGap> enriched = new ArrayList<>(gaps.size());
        for (MessagingFlowService.FlowGap gap : gaps) {
            enriched.add(enrichGap(gap, hops));
        }
        return enriched;
    }

    static MessagingFlowService.FlowGap enrichGap(
            MessagingFlowService.FlowGap gap,
            List<MessagingFlowService.CrossRepoHop> hops) {
        if (gap.hopOrder() != null && gap.topicShortId() != null) {
            return gap;
        }
        String topic = gap.topicShortId();
        if (topic == null || topic.isBlank()) {
            topic = extractTopicFromDescription(gap.description());
        }
        if (topic == null) {
            return gap;
        }
        Integer hopOrder = gap.hopOrder();
        if (hopOrder == null) {
            for (MessagingFlowService.CrossRepoHop hop : hops) {
                if (topic.equals(hop.topicShortId())) {
                    hopOrder = hop.order();
                    break;
                }
            }
        }
        if (topic == null && hopOrder == null) {
            return gap;
        }
        return new MessagingFlowService.FlowGap(
                gap.gapType(),
                gap.description(),
                hopOrder != null ? hopOrder : gap.hopOrder(),
                topic != null ? topic : gap.topicShortId());
    }

    static String extractTopicFromDescription(String description) {
        if (description == null) {
            return null;
        }
        Matcher matcher = TOPIC_IN_DESCRIPTION.matcher(description);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static List<MessagingFlowService.CrossRepoHopSummary> buildHopSummaries(
            List<MessagingFlowService.CrossRepoHop> hops) {
        List<MessagingFlowService.CrossRepoHopSummary> summaries = new ArrayList<>(hops.size());
        for (MessagingFlowService.CrossRepoHop hop : hops) {
            List<MessagingFlowService.HopParticipantSummary> publishers =
                    summarizeParticipants(hop.publishers());
            List<MessagingFlowService.HopParticipantSummary> subscribers =
                    summarizeParticipants(hop.subscribers());
            summaries.add(new MessagingFlowService.CrossRepoHopSummary(
                    hop.order(),
                    hop.topicShortId(),
                    hop.transport(),
                    publishers,
                    subscribers,
                    hopSummaryLine(hop.order(), hop.topicShortId(), hop.transport(),
                            publishers, subscribers, hop.terminalContinuations())));
        }
        return summaries;
    }

    private static List<MessagingFlowService.HopParticipantSummary> summarizeParticipants(
            List<MessagingFlowService.PubSubOrgView> participants) {
        if (participants == null || participants.isEmpty()) {
            return List.of();
        }
        return participants.stream()
                .map(CrossRepoFlowPresenter::toParticipantSummary)
                .toList();
    }

    static MessagingFlowService.HopParticipantSummary toParticipantSummary(
            MessagingFlowService.PubSubOrgView row) {
        String classSimple = simpleName(row.linkedClassFqn());
        String label = participantLabel(row.serviceName(), row.repo(), classSimple, row.role(), row.transport());
        return new MessagingFlowService.HopParticipantSummary(
                row.serviceName(),
                row.repo(),
                row.serviceId(),
                classSimple,
                row.role(),
                row.transport(),
                row.evidenceSource(),
                label);
    }

    private static String participantLabel(
            String serviceName, String repo, String classSimple, String role, String transport) {
        StringBuilder sb = new StringBuilder();
        sb.append(nullToDash(serviceName));
        if (repo != null && !repo.isBlank()) {
            sb.append(" (").append(repo).append(")");
        }
        if (classSimple != null && !classSimple.isBlank()) {
            sb.append(" → ").append(classSimple);
        }
        sb.append(" [").append(nullToDash(role));
        if (transport != null && !transport.isBlank()) {
            sb.append(", ").append(transport);
        }
        sb.append("]");
        return sb.toString();
    }

    private static String hopSummaryLine(
            int order,
            String topic,
            String transport,
            List<MessagingFlowService.HopParticipantSummary> publishers,
            List<MessagingFlowService.HopParticipantSummary> subscribers,
            List<MessagingFlowService.TerminalContinuationView> terminalContinuations) {
        String pub = publishers.isEmpty() ? "none" : joinLabels(publishers);
        String sub = subscribers.isEmpty() ? "none" : joinLabels(subscribers);
        String line = "Hop " + order + " · " + topic + " [" + nullToDash(transport)
                + "] · pub: " + pub + " · sub: " + sub;
        if (terminalContinuations != null && !terminalContinuations.isEmpty()) {
            line += " · terminal: " + terminalContinuations.size() + " continuation(s)";
        }
        return line;
    }

    private static String joinLabels(List<MessagingFlowService.HopParticipantSummary> participants) {
        return participants.stream()
                .map(MessagingFlowService.HopParticipantSummary::label)
                .reduce((a, b) -> a + "; " + b)
                .orElse("none");
    }

    private static List<String> buildNarrative(
            String startTopic,
            List<MessagingFlowService.CrossRepoHopSummary> hopSummaries,
            List<MessagingFlowService.FlowGap> gaps,
            String followMode,
            List<String> traceWarnings,
            int skippedExpansionCount) {
        List<String> lines = new ArrayList<>();
        lines.add("Cross-repo trace from " + startTopic
                + " (" + hopSummaries.size() + " hop(s), " + gaps.size() + " gap(s))");
        if (followMode != null && !followMode.isBlank()) {
            lines.add("BFS followMode=" + followMode
                    + (skippedExpansionCount > 0
                    ? " · skipped " + skippedExpansionCount + " manifest/catalog expansion(s)"
                    : ""));
        }
        if (traceWarnings != null) {
            traceWarnings.stream().filter(w -> w != null && !w.isBlank()).forEach(lines::add);
        }
        lines.add("");

        for (MessagingFlowService.CrossRepoHopSummary hop : hopSummaries) {
            lines.add("Hop " + hop.order() + " · " + hop.topicShortId() + " [" + nullToDash(hop.transport()) + "]");
            appendRoleLines(lines, "  ← publish", hop.publishers());
            appendRoleLines(lines, "  → subscribe", hop.subscribers());
            lines.add("");
        }

        if (!gaps.isEmpty()) {
            lines.add("Gaps:");
            for (MessagingFlowService.FlowGap gap : gaps) {
                String hopRef = gap.hopOrder() != null ? "hop " + gap.hopOrder() : "hop ?";
                String topicRef = gap.topicShortId() != null ? gap.topicShortId() : "?";
                lines.add("  [" + hopRef + "] " + gap.gapType() + " · " + topicRef
                        + " — " + gap.description());
            }
        }
        return List.copyOf(lines);
    }

    private static void appendRoleLines(
            List<String> lines,
            String prefix,
            List<MessagingFlowService.HopParticipantSummary> participants) {
        if (participants.isEmpty()) {
            lines.add(prefix + ": (none indexed)");
            return;
        }
        for (MessagingFlowService.HopParticipantSummary participant : participants) {
            lines.add(prefix + ": " + participant.label());
        }
    }

    private static String simpleName(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return "";
        }
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
