package io.testseer.backend.ingestion.consistency;

import io.testseer.backend.ingestion.FactBatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConsistencyScenarioExtractorTest {

    private final ConsistencyScenarioExtractor extractor = new ConsistencyScenarioExtractor();

    @Test
    void fromHandlerWrites_longHandlerNames_produceBoundedScenarioId() {
        List<FactBatch.DataAccessFact> touchpoints = List.of(
                write(
                        "com.example.AbstractSalesTransactionEvaluationProcessorDelegate",
                        "ignoreActivationIfRebateAlreadyRedeemed",
                        "MARIADB",
                        "UserOfferActivated",
                        "updateStatus"),
                write(
                        "com.example.AbstractSalesTransactionEvaluationProcessorDelegate",
                        "ignoreActivationIfRebateAlreadyRedeemed",
                        "MARIADB",
                        "SalesEvaluationAudit",
                        "insertAudit"));

        var scenarios = extractor.fromHandlerWrites(touchpoints);

        assertThat(scenarios).singleElement().satisfies(s -> {
            assertThat(s.scenarioId()).endsWith("-multi-table");
            assertThat(s.pattern()).isEqualTo("MULTI_TABLE_DOMAIN");
            assertThat(s.scenarioId().length()).isLessThanOrEqualTo(ConsistencyScenarioIds.MAX_LENGTH);
        });
    }

    @Test
    void fromHandlerWrites_dualWriteSameStore() {
        List<FactBatch.DataAccessFact> touchpoints = List.of(
                write("com.example.Adapter", "recordSubmission", "MARIADB",
                        "PartnerOfferCallRecorder", "saveToDb"),
                write("com.example.Adapter", "recordSubmission", "MARIADB",
                        "PartnerOfferCallRecorder", "markAllPendingAsProcessed"));

        var scenarios = extractor.fromHandlerWrites(touchpoints);

        assertThat(scenarios).singleElement().satisfies(s -> {
            assertThat(s.pattern()).isEqualTo("DUAL_WRITE_SAME_HANDLER");
            assertThat(s.scenarioId()).contains("dual-write");
            assertThat(s.scopeRef()).contains("recordSubmission");
        });
    }

    @Test
    void fromHandlerWrites_crossStore() {
        List<FactBatch.DataAccessFact> touchpoints = List.of(
                write("com.example.Handler", "save", "MARIADB", "UserTargeting", "insert"),
                write("com.example.Handler", "save", "MONGODB", "UserSegment", "upsert"));

        var scenarios = extractor.fromHandlerWrites(touchpoints);

        assertThat(scenarios).singleElement().satisfies(s -> {
            assertThat(s.pattern()).isEqualTo("CROSS_STORE_WRITE");
            assertThat(s.scenarioId()).endsWith("-cross-store");
            assertThat(s.participants()).contains("SECONDARY");
        });
    }

    @Test
    void fromEntityMirrors_asyncMirrorFromEntityAttributes() {
        String attributes = """
                {"mirrors":[{"storeType":"BIGQUERY","physicalName":"UserOfferActivated","via":"@LogForBigQuerySync","methodName":"save"}]}
                """;
        List<FactBatch.DataObjectFact> entities = List.of(new FactBatch.DataObjectFact(
                "com.example.UserOfferActivatedEntity",
                null,
                "CASSANDRA",
                "UserOfferActivated",
                null,
                "CQL_TABLE",
                "ENTITY_ANNOTATION+BQ_MIRROR",
                0.95,
                attributes
        ));

        var scenarios = extractor.fromEntityMirrors(entities);

        assertThat(scenarios).singleElement().satisfies(s -> {
            assertThat(s.pattern()).isEqualTo("ASYNC_MIRROR");
            assertThat(s.scenarioId()).isEqualTo("userofferactivated-bq-mirror");
            assertThat(s.participants()).contains("BIGQUERY");
        });
    }

    @Test
    void fromHandlerReads_dualReadFallback() {
        List<FactBatch.DataAccessFact> touchpoints = List.of(
                read("com.example.Reader", "load", "CASSANDRA", "UserOffer"),
                read("com.example.Reader", "load", "MARIADB", "UserOffer"));

        var scenarios = extractor.fromHandlerReads(touchpoints);

        assertThat(scenarios).singleElement().satisfies(s -> {
            assertThat(s.pattern()).isEqualTo("DUAL_READ_FALLBACK");
            assertThat(s.scenarioId()).endsWith("-dual-read");
        });
    }

    private static FactBatch.DataAccessFact write(
            String handler, String method, String store, String table, String daoMethod) {
        return FactBatch.DataAccessFact.linked(
                handler, method, "WRITE", store, table,
                "dao", daoMethod, "[\"offerId\"]", null,
                "HANDLER_LINKER+CATALOG", 0.93,
                null, null, "com.example.Dao", "DAO", null, null);
    }

    private static FactBatch.DataAccessFact read(
            String handler, String method, String store, String table) {
        return FactBatch.DataAccessFact.linked(
                handler, method, "READ", store, table,
                "dao", "find", "[]", null,
                "HANDLER_LINKER+CATALOG", 0.93,
                null, null, "com.example.Dao", "DAO", null, null);
    }
}
