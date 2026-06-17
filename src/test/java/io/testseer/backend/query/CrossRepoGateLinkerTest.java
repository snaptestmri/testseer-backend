package io.testseer.backend.query;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CrossRepoGateLinkerTest {

    private final CrossRepoGateLinker linker = new CrossRepoGateLinker();

    @Test
    void attachDownstreamGates_linksGateInLaterHopByTablePrefix() {
        ConsistencyHintView hint = new ConsistencyHintView(
                "offer-pidmap-gate",
                "CO_TABLE_INVARIANT",
                "MARIADB",
                "Offer",
                List.of("offerId", "partnerId"),
                List.of(
                        new ConsistencyParticipantHintView(
                                "MARIADB", "Offer", "PRIMARY", null, null),
                        new ConsistencyParticipantHintView(
                                "MARIADB", "OfferPidMap", "REQUIRED_CHILD", null, null)),
                null,
                List.of(),
                "RULE_PACK",
                0.95,
                List.of());

        MessagingFlowService.PubSubOrgView writerSub = pubSub("adapter-svc", "adapter-repo", 1);
        MessagingFlowService.PubSubOrgView galoSub = pubSub("galo-svc", "galo-repo", 2);

        MessagingFlowService.CrossRepoHop hop1 = new MessagingFlowService.CrossRepoHop(
                1, "PDN_T.RIQ_OFFER_EVENT", List.of(), List.of(writerSub));
        MessagingFlowService.CrossRepoHop hop2 = new MessagingFlowService.CrossRepoHop(
                2, "PDN_T.NEXT", List.of(), List.of(galoSub));

        MessagingFlowService.FlowGateView gate = new MessagingFlowService.FlowGateView(
                "com.example.OfferService",
                "GALO_READ",
                "BUSINESS_RULE",
                "OfferPidMap.IsPublished",
                "true",
                "SKIP",
                "must be published",
                "JAVA_AST",
                0.8,
                null, "UNKNOWN", null, null);

        CrossRepoTraceContext ctx = new CrossRepoTraceContext(
                "quotient",
                "pdn",
                List.of(hop1, hop2),
                Set.of("adapter-svc", "galo-svc"),
                java.util.Map.of(),
                java.util.Map.of("galo-svc", List.of(gate)), null);

        ConsistencyHintView enriched = linker.attachDownstreamGates(hint, 1, ctx);

        assertThat(enriched.downstreamGates()).singleElement().satisfies(dg -> {
            assertThat(dg.serviceId()).isEqualTo("galo-svc");
            assertThat(dg.hopOrder()).isEqualTo(2);
            assertThat(dg.gateKey()).isEqualTo("OfferPidMap.IsPublished");
        });
    }

    @Test
    void attachDownstreamGates_skipsSameOrEarlierHop() {
        ConsistencyHintView hint = new ConsistencyHintView(
                "offer-pidmap-gate", "CO_TABLE_INVARIANT", "MARIADB", "Offer",
                List.of(), List.of(), null, List.of(), "RULE_PACK", 0.9, List.of());

        MessagingFlowService.FlowGateView gate = new MessagingFlowService.FlowGateView(
                "com.example.Svc", null, "BUSINESS_RULE", "Offer.IsPublished",
                "true", "SKIP", "pre", "JAVA_AST", 0.8, null, "UNKNOWN", null, null);

        MessagingFlowService.CrossRepoHop hop1 = new MessagingFlowService.CrossRepoHop(
                1, "T", List.of(), List.of(pubSub("svc", "repo", 1)));

        CrossRepoTraceContext ctx = new CrossRepoTraceContext(
                "quotient", "pdn", List.of(hop1), Set.of("svc"),
                java.util.Map.of(), java.util.Map.of("svc", List.of(gate)), null);

        assertThat(linker.attachDownstreamGates(hint, 1, ctx).downstreamGates()).isEmpty();
    }

    private static MessagingFlowService.PubSubOrgView pubSub(
            String serviceId, String repo, int hopOrderUnused) {
        return new MessagingFlowService.PubSubOrgView(
                serviceId, repo, "name", "SUBSCRIPTION", "PDN_S.T", "pdn",
                "SUBSCRIBE", null, null, "module", "com.example.Handler",
                "workload", "YAML", 1.0, "PUBSUB", List.of(), List.of(), null);
    }
}
