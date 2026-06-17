package io.testseer.backend.ingestion.catalog;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.messaging.ProtoSchemaExtractor;
import io.testseer.backend.query.CatalogResolverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MongoAccessExtractorTest {

    @Mock CatalogResolverService catalogResolver;
    @Mock JdbcClient db;

    MongoAccessExtractor extractor;

    @BeforeEach
    void setUp() {
        TypeFqnResolver typeFqnResolver = new TypeFqnResolver(db, catalogResolver);
        extractor = new MongoAccessExtractor(catalogResolver, typeFqnResolver);
    }

    private static final String REPO_FQN =
            "com.quotient.platform.data.mongo.clusters.riq.offers.repo.SegmentOffersRepo";
    private static final String ENTITY_FQN =
            "com.quotient.platform.data.mongo.clusters.riq.offers.entity.SegmentOfferEntity";

    @Test
    void extract_mongoRepoSave() {
        when(catalogResolver.findAccessorMethod(eq("acme"), eq(REPO_FQN), eq("save")))
                .thenReturn(Optional.of(new CatalogResolverService.AccessorMethodRow(
                        REPO_FQN, "save", "WRITE", ENTITY_FQN, null,
                        "MONGODB", "segment_offers", null, 0.90)));

        String java = """
                package com.example;
                import com.quotient.platform.data.mongo.clusters.riq.offers.repo.SegmentOffersRepo;
                public class OfferWriter {
                    private SegmentOffersRepo segmentOffersRepo;
                    public void write() { segmentOffersRepo.save(entity); }
                }
                """;
        var files = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "OfferWriter.java", java, "com.example.OfferWriter"));

        List<FactBatch.DataAccessFact> facts = extractor.extract("acme", "svc-writer", files);

        assertThat(facts).singleElement().satisfies(f -> {
            assertThat(f.storeType()).isEqualTo("MONGODB");
            assertThat(f.tableOrEntity()).isEqualTo("segment_offers");
            assertThat(f.entityFqn()).isEqualTo(ENTITY_FQN);
        });
    }
}
