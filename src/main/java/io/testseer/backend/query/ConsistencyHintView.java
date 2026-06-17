package io.testseer.backend.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lightweight consistency scenario attached to event-flow steps or data-access rows. */
public record ConsistencyHintView(
        String scenarioId,
        String pattern,
        String primaryStore,
        String primaryPhysical,
        List<String> correlationKeys,
        List<ConsistencyParticipantHintView> participants,
        ConsistencyPollStrategyView pollStrategy,
        List<ConsistencyInvariantHintView> invariants,
        String evidenceSource,
        double confidence,
        List<DownstreamGateView> downstreamGates
) {
    static ConsistencyHintView fromScenario(ConsistencyQueryService.ConsistencyScenarioView s) {
        return new ConsistencyHintView(
                s.scenarioId(),
                s.pattern(),
                s.primaryStore(),
                s.primaryPhysical(),
                s.correlationKeys(),
                s.participants(),
                s.pollStrategy(),
                s.invariants(),
                s.evidenceSource(),
                s.confidence(),
                List.of()
        );
    }

    ConsistencyHintView withDownstreamGates(List<DownstreamGateView> gates) {
        return new ConsistencyHintView(
                scenarioId, pattern, primaryStore, primaryPhysical,
                correlationKeys, participants, pollStrategy, invariants,
                evidenceSource, confidence,
                gates != null ? List.copyOf(gates) : List.of()
        );
    }

    ConsistencyHintView withInvariants(List<ConsistencyInvariantHintView> derived) {
        if (derived == null || derived.isEmpty()) {
            return this;
        }
        Map<String, ConsistencyInvariantHintView> merged = new LinkedHashMap<>();
        if (invariants != null) {
            for (ConsistencyInvariantHintView inv : invariants) {
                merged.put(invariantKey(inv), inv);
            }
        }
        for (ConsistencyInvariantHintView inv : derived) {
            merged.putIfAbsent(invariantKey(inv), inv);
        }
        return new ConsistencyHintView(
                scenarioId, pattern, primaryStore, primaryPhysical,
                correlationKeys, participants, pollStrategy, List.copyOf(merged.values()),
                evidenceSource, confidence, downstreamGates);
    }

    private static String invariantKey(ConsistencyInvariantHintView inv) {
        return (inv.kind() != null ? inv.kind() : "") + "|" + (inv.description() != null ? inv.description() : "");
    }
}
