package io.testseer.backend.ingestion.catalog;

import io.testseer.backend.ingestion.FactBatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MirrorStoreExtractorTest {

    private static final String REPO = """
            package com.quotient.platform.data.nosql.riq.nextgenactivation.repo;
            import com.quotient.platform.data.bqsync.LogForBigQuerySync;
            import com.quotient.platform.data.bqsync.Operation;
            import com.quotient.platform.data.nosql.repo.BaseNoSqlRepository;
            import com.quotient.platform.data.nosql.riq.nextgenactivation.entities.UserOfferActivatedEntity;
            public interface UserOfferActivatedRepo extends BaseNoSqlRepository<UserOfferActivatedEntity> {
                @LogForBigQuerySync(tableName = "UserOfferActivated", operation = Operation.UPDATE, keyFields = {"PartnerId", "UserId", "ActivationId"})
                void updateVisibilityFlag(Boolean visible, String partnerId, String userId, String activationId);
            }
            """;

    private final MirrorStoreExtractor extractor = new MirrorStoreExtractor();

    @Test
    void extract_findsBigQueryMirrorAnnotation() {
        var files = List.of(new io.testseer.backend.ingestion.messaging.ProtoSchemaExtractor.JavaSourceFile(
                "UserOfferActivatedRepo.java", REPO,
                "com.quotient.platform.data.nosql.riq.nextgenactivation.repo.UserOfferActivatedRepo"));

        List<MirrorStoreExtractor.MirrorRef> mirrors = extractor.extract(files);

        assertThat(mirrors).singleElement().satisfies(m -> {
            assertThat(m.tableName()).isEqualTo("UserOfferActivated");
            assertThat(m.methodName()).isEqualTo("updateVisibilityFlag");
            assertThat(m.keyFields()).containsExactly("PartnerId", "UserId", "ActivationId");
        });
    }

    @Test
    void attachMirrorsToEntities_mergesIntoAttributes() {
        var entity = new FactBatch.DataObjectFact(
                "com.quotient.platform.data.nosql.riq.nextgenactivation.entities.UserOfferActivatedEntity",
                null, "CASSANDRA", "UserOfferActivated", null, "CQL_TABLE",
                "ENTITY_ANNOTATION", 0.95, null);
        var repos = List.of(new RepoGenericExtractor.RepoLink(
                "com.quotient.platform.data.nosql.riq.nextgenactivation.repo.UserOfferActivatedRepo",
                entity.entityFqn(), "UserOfferActivatedEntity", StoreType.CASSANDRA));
        var mirrors = List.of(new MirrorStoreExtractor.MirrorRef(
                repos.get(0).accessorFqn(), "updateVisibilityFlag", "UserOfferActivated", "UPDATE",
                List.of("PartnerId", "UserId", "ActivationId")));

        List<FactBatch.DataObjectFact> enriched = extractor.attachMirrorsToEntities(
                List.of(entity), mirrors, repos);

        assertThat(enriched.get(0).attributes()).contains("UserOfferActivated");
        assertThat(enriched.get(0).attributes()).contains("BIGQUERY");
        assertThat(enriched.get(0).evidenceSource()).contains("BQ_MIRROR");

        String secondary = CatalogAttributesHelper.secondaryStoresForMethod(
                enriched.get(0).attributes(), repos.get(0).accessorFqn(), "updateVisibilityFlag");
        assertThat(secondary).contains("ASYNC_MIRROR");
    }
}
