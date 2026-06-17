package io.testseer.backend.ingestion.catalog;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.messaging.ProtoSchemaExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CassandraQueryExtractorTest {

    private static final String REPO = """
            package com.quotient.platform.data.nosql.riq.nextgenactivation.repo;
            import com.quotient.platform.data.nosql.repo.BaseNoSqlRepository;
            import com.quotient.platform.data.nosql.riq.nextgenactivation.entities.UserOfferActivatedEntity;
            import org.springframework.data.cassandra.repository.Query;
            public interface UserOfferActivatedRepo extends BaseNoSqlRepository<UserOfferActivatedEntity> {
                @Query("UPDATE \\"UserOfferActivated\\" SET \\"Visible\\" = :visible WHERE \\"PartnerId\\" = :partnerId")
                void updateVisibilityFlag(Boolean visible, String partnerId, String userId, String activationId);
                void save(UserOfferActivatedEntity entity);
            }
            """;

    private final CassandraQueryExtractor extractor = new CassandraQueryExtractor(new StoreTypeInferencer());

    @Test
    void extractAndMerge_cassandraQueryMethod() {
        var files = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "UserOfferActivatedRepo.java", REPO,
                "com.quotient.platform.data.nosql.riq.nextgenactivation.repo.UserOfferActivatedRepo"));
        var entity = new FactBatch.DataObjectFact(
                "com.quotient.platform.data.nosql.riq.nextgenactivation.entities.UserOfferActivatedEntity",
                null, "CASSANDRA", "UserOfferActivated", null, "CQL_TABLE",
                "ENTITY_ANNOTATION", 0.95, null);

        List<FactBatch.AccessorMethodFact> facts = extractor.extractAndMerge(files, List.of(entity), List.of());

        assertThat(facts).anyMatch(f ->
                "updateVisibilityFlag".equals(f.methodName())
                        && "CASSANDRA".equals(f.storeType())
                        && "UserOfferActivated".equals(f.physicalName())
                        && "WRITE".equals(f.operation()));
        assertThat(facts).anyMatch(f -> "save".equals(f.methodName()) && "WRITE".equals(f.operation()));
    }

    @Test
    void extractCqlTable_parsesQuotedTable() {
        assertThat(CassandraQueryExtractor.extractCqlTable(
                "UPDATE \"UserOfferActivated\" SET \"Visible\" = :visible")).isEqualTo("UserOfferActivated");
        assertThat(CassandraQueryExtractor.operationFromCql("SELECT * FROM \"Foo\"")).isEqualTo("READ");
    }
}
