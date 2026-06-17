package io.testseer.backend.ingestion.catalog;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.messaging.ProtoSchemaExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EntityCatalogExtractorTest {

    private final EntityCatalogExtractor extractor =
            new EntityCatalogExtractor(new StoreTypeInferencer(), new EntityAnnotationParser());

    @Test
    void extract_mariadbEntityWithTable() {
        String java = """
                package com.quotient.platform.data.rdb.dataaccess.offer.entities.offer;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                @Entity
                @Table(name = "PartnerOfferCallRecorder", catalog = "coupons_nextgen")
                public class PartnerOfferCallRecorderEntity {}
                """;
        var files = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "PartnerOfferCallRecorderEntity.java", java,
                "com.quotient.platform.data.rdb.dataaccess.offer.entities.offer.PartnerOfferCallRecorderEntity"));

        List<FactBatch.DataObjectFact> facts = extractor.extract(files);

        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).storeType()).isEqualTo("MARIADB");
        assertThat(facts.get(0).physicalName()).isEqualTo("PartnerOfferCallRecorder");
        assertThat(facts.get(0).catalogOrKeyspace()).isEqualTo("coupons_nextgen");
        assertThat(facts.get(0).domainFqn()).isEqualTo(
                "com.quotient.platform.domain.offer.entities.offer.PartnerOfferCallRecorder");
    }

    @Test
    void extract_mongoDocument() {
        String java = """
                package com.quotient.platform.data.mongo.clusters.riq.offers.entity;
                import org.springframework.data.mongodb.core.mapping.Document;
                @Document(collection = "segment_offers")
                public class SegmentOfferEntity {}
                """;
        var files = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "SegmentOfferEntity.java", java,
                "com.quotient.platform.data.mongo.clusters.riq.offers.entity.SegmentOfferEntity"));

        List<FactBatch.DataObjectFact> facts = extractor.extract(files);

        assertThat(facts).singleElement().satisfies(f -> {
            assertThat(f.storeType()).isEqualTo("MONGODB");
            assertThat(f.physicalName()).isEqualTo("segment_offers");
            assertThat(f.collectionOrTableKind()).isEqualTo("COLLECTION");
        });
    }

    @Test
    void inferDomainFqn_stripsEntitySuffix() {
        assertThat(EntityCatalogExtractor.inferDomainFqn(
                "com.quotient.platform.data.rdb.dataaccess.offer.entities.offer.PartnerOfferCallRecorderEntity"))
                .isEqualTo("com.quotient.platform.domain.offer.entities.offer.PartnerOfferCallRecorder");
    }

    @Test
    void extract_singleQuotedTableName() {
        String java = """
                package com.example;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;
                @Entity
                @Table(name = 'PartnerOfferCallRecorder', catalog = 'coupons_nextgen')
                public class PartnerOfferCallRecorderEntity {}
                """;
        var files = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "PartnerOfferCallRecorderEntity.java", java,
                "com.example.PartnerOfferCallRecorderEntity"));

        List<FactBatch.DataObjectFact> facts = extractor.extract(files);

        assertThat(facts).singleElement().satisfies(f ->
                assertThat(f.physicalName()).isEqualTo("PartnerOfferCallRecorder"));
    }

    @Test
    void extract_kotlinMultiDocumentFile() {
        String kt = """
                package com.quotient.platform.nre.libs.mongo.model
                import org.springframework.data.mongodb.core.mapping.Document
                @Document(collection = "user_recommendations")
                data class UserRecommendations(val id: String)
                @Document(collection = "job_info")
                data class JobInfo(val jobId: String)
                """;
        var files = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "MongoModels.kt", kt,
                "com.quotient.platform.nre.libs.mongo.model.UserRecommendations"));

        List<FactBatch.DataObjectFact> facts = extractor.extract(files);

        assertThat(facts).hasSize(2);
        assertThat(facts).extracting(FactBatch.DataObjectFact::physicalName)
                .containsExactlyInAnyOrder("user_recommendations", "job_info");
    }
}
