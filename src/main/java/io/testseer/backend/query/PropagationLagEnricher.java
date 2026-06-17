package io.testseer.backend.query;

import io.testseer.backend.config.PropagationTopologyLoader;
import io.testseer.backend.config.WorkspaceConfig;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Query-time PROPAGATION_LAG hints from workspace propagation edges (BL-041). */
@Component
public class PropagationLagEnricher {

    private final PropagationTopologyLoader topologyLoader;
    private final CrossRepoGateLinker gateLinker;

    public PropagationLagEnricher(
            PropagationTopologyLoader topologyLoader,
            CrossRepoGateLinker gateLinker) {
        this.topologyLoader = topologyLoader;
        this.gateLinker = gateLinker;
    }

    public List<MessagingFlowService.CrossRepoHop> enrichHops(
            List<MessagingFlowService.CrossRepoHop> hops,
            CrossRepoTraceContext ctx) {
        if (ctx == null || ctx.orgId() == null || ctx.bundleName() == null) {
            return hops;
        }
        List<WorkspaceConfig.PropagationEdgeConfig> edges =
                topologyLoader.edgesForBundle(ctx.orgId(), ctx.bundleName());
        if (edges.isEmpty()) {
            return hops;
        }

        List<MessagingFlowService.CrossRepoHop> enriched = new ArrayList<>();
        for (MessagingFlowService.CrossRepoHop hop : hops) {
            enriched.add(enrichHop(hop, edges, ctx));
        }
        return enriched;
    }

    private MessagingFlowService.CrossRepoHop enrichHop(
            MessagingFlowService.CrossRepoHop hop,
            List<WorkspaceConfig.PropagationEdgeConfig> edges,
            CrossRepoTraceContext ctx) {
        List<MessagingFlowService.PubSubOrgView> publishers = new ArrayList<>();
        for (MessagingFlowService.PubSubOrgView pub : hop.publishers()) {
            publishers.add(attachPropagationHints(pub, hop.order(), edges, ctx));
        }
        List<MessagingFlowService.PubSubOrgView> subscribers = new ArrayList<>();
        for (MessagingFlowService.PubSubOrgView sub : hop.subscribers()) {
            subscribers.add(attachPropagationHints(sub, hop.order(), edges, ctx));
        }
        return new MessagingFlowService.CrossRepoHop(
                hop.order(), hop.topicShortId(), hop.transport(), publishers, subscribers,
                hop.terminalContinuations());
    }

    private MessagingFlowService.PubSubOrgView attachPropagationHints(
            MessagingFlowService.PubSubOrgView node,
            int hopOrder,
            List<WorkspaceConfig.PropagationEdgeConfig> edges,
            CrossRepoTraceContext ctx) {
        List<MessagingFlowService.DataAccessView> touchpoints =
                ctx.dataAccessByService().getOrDefault(node.serviceId(), List.of());
        List<ConsistencyHintView> hints = new ArrayList<>(node.consistencyHints());
        for (WorkspaceConfig.PropagationEdgeConfig edge : edges) {
            if (!node.serviceId().equals(edge.authoritative().serviceId())) {
                continue;
            }
            if (!touchpointsIncludeAuthoritative(touchpoints, edge)) {
                continue;
            }
            ConsistencyHintView hint = hintFromEdge(edge);
            hint = gateLinker.attachDownstreamGates(hint, hopOrder, ctx);
            hints.add(hint);
        }
        if (hints.size() == node.consistencyHints().size()) {
            return node;
        }
        return node.withConsistencyHints(hints);
    }

    private static boolean touchpointsIncludeAuthoritative(
            List<MessagingFlowService.DataAccessView> touchpoints,
            WorkspaceConfig.PropagationEdgeConfig edge) {
        String physical = normalize(edge.authoritative().physicalName());
        for (MessagingFlowService.DataAccessView row : touchpoints) {
            if (!"WRITE".equalsIgnoreCase(row.operation())) continue;
            if (physicalMatches(row.physicalName(), physical)
                    || physicalMatches(row.tableOrEntity(), physical)) {
                return true;
            }
        }
        return false;
    }

    private static ConsistencyHintView hintFromEdge(WorkspaceConfig.PropagationEdgeConfig edge) {
        List<ConsistencyParticipantHintView> participants = new ArrayList<>();
        participants.add(new ConsistencyParticipantHintView(
                edge.authoritative().storeType(),
                edge.authoritative().physicalName(),
                "PRIMARY",
                null,
                "SYNC"));
        if (edge.peripheral() != null) {
            for (WorkspaceConfig.PropagationPeripheralConfig p : edge.peripheral()) {
                participants.add(new ConsistencyParticipantHintView(
                        p.storeType(), p.physicalName(), "MIRROR", null, p.lagClass()));
            }
        }
        ConsistencyPollStrategyView poll = edge.pollStrategy() != null
                ? new ConsistencyPollStrategyView(
                        edge.pollStrategy().order(),
                        edge.pollStrategy().primaryPollHint(),
                        edge.pollStrategy().notes())
                : null;
        return new ConsistencyHintView(
                edge.id(),
                edge.pattern() != null ? edge.pattern() : "PROPAGATION_LAG",
                edge.authoritative().storeType(),
                edge.authoritative().physicalName(),
                edge.correlationKeys(),
                participants,
                poll,
                List.of(),
                "PROPAGATION_TOPOLOGY",
                0.90,
                List.of());
    }

    private static boolean physicalMatches(String candidate, String normalizedTarget) {
        return candidate != null && normalize(candidate).equals(normalizedTarget);
    }

    private static String normalize(String name) {
        return name.replace("_", "").toLowerCase(Locale.ROOT);
    }
}
