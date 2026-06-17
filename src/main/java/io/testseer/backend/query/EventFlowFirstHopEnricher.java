package io.testseer.backend.query;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * TRG-14: links indexed inbound entry triggers onto the first handler hop of event-flow traces.
 */
@Service
public class EventFlowFirstHopEnricher {

    private final EntryFlowService entryFlowService;

    public EventFlowFirstHopEnricher(EntryFlowService entryFlowService) {
        this.entryFlowService = entryFlowService;
    }

    public List<MessagingFlowService.EventFlowStep> enrichFirstStep(
            String serviceId, List<MessagingFlowService.EventFlowStep> steps) {
        if (steps.isEmpty()) {
            return steps;
        }
        MessagingFlowService.EventFlowStep first = steps.get(0);
        if (first.handler() == null || first.handler().isBlank()) {
            return steps;
        }
        List<EntryFlowService.EntryTriggerView> triggers =
                entryFlowService.triggersForHandler(serviceId, first.handler());
        if (triggers.isEmpty()) {
            return steps;
        }
        List<MessagingFlowService.EventFlowStep> enriched = new ArrayList<>(steps);
        enriched.set(0, first.withInboundTriggers(triggers));
        return enriched;
    }

    public List<MessagingFlowService.CrossRepoHop> enrichFirstHopSubscribers(
            List<MessagingFlowService.CrossRepoHop> hops) {
        if (hops.isEmpty() || hops.get(0).subscribers().isEmpty()) {
            return hops;
        }
        MessagingFlowService.CrossRepoHop firstHop = hops.get(0);
        List<MessagingFlowService.PubSubOrgView> subscribers = new ArrayList<>(firstHop.subscribers());
        MessagingFlowService.PubSubOrgView firstSub = subscribers.get(0);
        if (firstSub.linkedClassFqn() == null || firstSub.linkedClassFqn().isBlank()) {
            return hops;
        }
        List<EntryFlowService.EntryTriggerView> triggers = entryFlowService.triggersForHandler(
                firstSub.serviceId(), firstSub.linkedClassFqn());
        if (triggers.isEmpty()) {
            return hops;
        }
        subscribers.set(0, firstSub.withInboundTriggers(triggers));
        List<MessagingFlowService.CrossRepoHop> enriched = new ArrayList<>(hops);
        enriched.set(0, new MessagingFlowService.CrossRepoHop(
                firstHop.order(), firstHop.topicShortId(), firstHop.transport(), firstHop.publishers(),
                subscribers, firstHop.terminalContinuations()));
        return enriched;
    }
}
