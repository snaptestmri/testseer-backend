package io.testseer.backend.ingestion.catalog;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.GitHubSourceFetcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaDdlExtractorTest {

    private final SchemaDdlExtractor extractor = new SchemaDdlExtractor();

    @Test
    void extract_mariaDbTableFromSql() {
        String sql = """
                CREATE TABLE IF NOT EXISTS coupons_nextgen.PartnerOffers  (
                  OfferId BIGINT NOT NULL,
                  PRIMARY KEY (OfferId)
                );
                """;
        var files = List.of(new GitHubSourceFetcher.FetchedFile(
                "MariaDB/coupons_nextgen/Tables/PartnerOffers.sql", sql));

        List<FactBatch.SchemaObjectFact> facts = extractor.extract(files);

        assertThat(facts).singleElement().satisfies(f -> {
            assertThat(f.storeType()).isEqualTo("MARIADB");
            assertThat(f.physicalName()).isEqualTo("PartnerOffers");
            assertThat(f.catalogOrKeyspace()).isEqualTo("coupons_nextgen");
            assertThat(f.ddlPath()).contains("PartnerOffers.sql");
        });
    }

    @Test
    void extract_cassandraTableFromCql() {
        String cql = """
                CREATE TABLE "UserOfferActivated" (
                  "PartnerId" text,
                  "UserId" text,
                  PRIMARY KEY (("PartnerId", "UserId"))
                );
                """;
        var files = List.of(new GitHubSourceFetcher.FetchedFile(
                "Cassandra/CouponsNextgenActivation/Tables/UserOfferActivated_DSE46.cql", cql));

        List<FactBatch.SchemaObjectFact> facts = extractor.extract(files);

        assertThat(facts).singleElement().satisfies(f -> {
            assertThat(f.storeType()).isEqualTo("CASSANDRA");
            assertThat(f.physicalName()).isEqualTo("UserOfferActivated");
            assertThat(f.catalogOrKeyspace()).isEqualTo("CouponsNextgenActivation");
        });
    }

    @Test
    void inferCatalogFromPath() {
        assertThat(SchemaDdlExtractor.inferMariaCatalog("MariaDB/coupons_nextgen/Tables/Foo.sql"))
                .isEqualTo("coupons_nextgen");
        assertThat(SchemaDdlExtractor.inferCassandraKeyspace(
                "Cassandra/CouponsNextgenActivation/Tables/UserOfferActivated.cql"))
                .isEqualTo("CouponsNextgenActivation");
    }
}
