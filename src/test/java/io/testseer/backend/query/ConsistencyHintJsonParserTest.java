package io.testseer.backend.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConsistencyHintJsonParserTest {

    @Test
    void parseParticipants_mapsStructuredFields() {
        String json = """
                [{"storeType":"MARIADB","physicalName":"Offer","role":"PRIMARY","via":null,"lagClass":null}]
                """;
        List<ConsistencyParticipantHintView> participants = ConsistencyHintJsonParser.parseParticipants(json);
        assertThat(participants).singleElement().satisfies(p -> {
            assertThat(p.storeType()).isEqualTo("MARIADB");
            assertThat(p.physicalName()).isEqualTo("Offer");
            assertThat(p.role()).isEqualTo("PRIMARY");
        });
    }

    @Test
    void parseInvariants_mapsStructuredFields() {
        String json = """
                [{"kind":"ROW_EXISTS","description":"gate","pollHint":"poll OfferPidMap"}]
                """;
        List<ConsistencyInvariantHintView> invariants = ConsistencyHintJsonParser.parseInvariants(json);
        assertThat(invariants).singleElement().satisfies(i -> {
            assertThat(i.kind()).isEqualTo("ROW_EXISTS");
            assertThat(i.description()).isEqualTo("gate");
            assertThat(i.pollHint()).isEqualTo("poll OfferPidMap");
        });
    }

    @Test
    void aggregateCrossRepoHints_deduplicatesByScenarioId() {
        ConsistencyHintView hint = new ConsistencyHintView(
                "s1", "DUAL_WRITE", "MARIADB", "Offer",
                List.of(), List.of(), null, List.of(), "RULE_PACK", 0.9, List.of());
        MessagingFlowService.PubSubOrgView sub = new MessagingFlowService.PubSubOrgView(
                "svc", "repo", "n", "SUBSCRIPTION", "T", "pdn", "SUBSCRIBE",
                null, null, "m", "h", "w", "YAML", 1.0, "PUBSUB", List.of(hint), List.of(), null);
        MessagingFlowService.CrossRepoHop hop = new MessagingFlowService.CrossRepoHop(
                1, "T", List.of(), List.of(sub, sub));

        List<ConsistencyHintView> aggregated = MessagingFlowService.aggregateCrossRepoHints(List.of(hop));
        assertThat(aggregated).hasSize(1).first().isEqualTo(hint);
    }
}
