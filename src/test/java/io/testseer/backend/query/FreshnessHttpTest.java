package io.testseer.backend.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FreshnessHttpTest {

    @Test
    void notIndexed_returns404() {
        var response = FreshnessHttp.respond(FreshnessStatus.NOT_INDEXED, "data");
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().freshnessStatus()).isEqualTo(FreshnessStatus.NOT_INDEXED);
        assertThat(response.getBody().data()).isNull();
    }

    @Test
    void indexing_returns202() {
        var response = FreshnessHttp.respond(FreshnessStatus.INDEXING, "partial");
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().freshnessStatus()).isEqualTo(FreshnessStatus.INDEXING);
        assertThat(response.getBody().data()).isEqualTo("partial");
    }

    @Test
    void current_returns200() {
        var response = FreshnessHttp.respond(FreshnessStatus.CURRENT, "full");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().freshnessStatus()).isEqualTo(FreshnessStatus.CURRENT);
    }
}
