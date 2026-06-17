package io.testseer.backend.ingestion.catalog;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.messaging.ProtoSchemaExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RepoGenericExtractorTest {

    private final RepoGenericExtractor extractor =
            new RepoGenericExtractor(new StoreTypeInferencer());

    @Test
    void extract_linksJpaRepositoryToEntity() {
        String java = """
                package com.quotient.platform.data.rdb.repo.readwrite.offer;
                import com.quotient.platform.data.rdb.dataaccess.offer.entities.offer.PartnerOfferCallRecorderEntity;
                import org.springframework.data.jpa.repository.JpaRepository;
                public interface PartnerOfferCallRecorderWriteRepo
                        extends JpaRepository<PartnerOfferCallRecorderEntity, String> {}
                """;
        var files = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "PartnerOfferCallRecorderWriteRepo.java", java,
                "com.quotient.platform.data.rdb.repo.readwrite.offer.PartnerOfferCallRecorderWriteRepo"));

        List<RepoGenericExtractor.RepoLink> links = extractor.extract(files);

        assertThat(links).singleElement().satisfies(l -> {
            assertThat(l.entityFqn()).isEqualTo(
                    "com.quotient.platform.data.rdb.dataaccess.offer.entities.offer.PartnerOfferCallRecorderEntity");
            assertThat(l.storeType()).isEqualTo(StoreType.MARIADB);
        });
    }

    @Test
    void enrichWithRepos_addsAccessorToAttributes() {
        var entity = new FactBatch.DataObjectFact(
                "com.example.FooEntity", null, "MARIADB", "Foo", null, "TABLE",
                "ENTITY_ANNOTATION", 0.95, null);
        var repos = List.of(new RepoGenericExtractor.RepoLink(
                "com.example.FooRepo", "com.example.FooEntity", "FooEntity", StoreType.MARIADB));

        List<FactBatch.DataObjectFact> enriched = extractor.enrichWithRepos(List.of(entity), repos);

        assertThat(enriched.get(0).attributes()).contains("com.example.FooRepo");
        assertThat(enriched.get(0).evidenceSource()).isEqualTo("ENTITY_ANNOTATION+REPO_GENERIC");
    }

    @Test
    void enrichWithRepos_dedupesEvidenceSourceTagForMultipleReposOnSameEntity() {
        var entity = new FactBatch.DataObjectFact(
                "com.example.FooEntity", null, "MARIADB", "Foo", null, "TABLE",
                "ENTITY_ANNOTATION", 0.95, null);
        var repos = List.of(
                new RepoGenericExtractor.RepoLink(
                        "com.example.FooWriteRepo", "com.example.FooEntity", "FooEntity", StoreType.MARIADB),
                new RepoGenericExtractor.RepoLink(
                        "com.example.FooReadRepo", "com.example.FooEntity", "FooEntity", StoreType.MARIADB),
                new RepoGenericExtractor.RepoLink(
                        "com.example.FooLegacyRepo", "com.example.FooEntity", "FooEntity", StoreType.MARIADB));

        List<FactBatch.DataObjectFact> enriched = extractor.enrichWithRepos(List.of(entity), repos);

        assertThat(enriched.get(0).evidenceSource()).isEqualTo("ENTITY_ANNOTATION+REPO_GENERIC");
        assertThat(enriched.get(0).evidenceSource().length()).isLessThanOrEqualTo(EvidenceSources.MAX_LENGTH);
    }
}
