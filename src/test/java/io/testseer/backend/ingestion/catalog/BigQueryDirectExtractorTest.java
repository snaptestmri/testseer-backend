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

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class BigQueryDirectExtractorTest {

    @Mock JdbcClient db;
    @Mock CatalogResolverService catalogResolver;

    BigQueryDirectExtractor extractor;

    @BeforeEach
    void setUp() {
        TypeFqnResolver typeFqnResolver = new TypeFqnResolver(db, catalogResolver);
        extractor = new BigQueryDirectExtractor(typeFqnResolver);
    }

    @Test
    void extract_bigQueryUtilSave() {
        String java = """
                package com.example;
                import com.quotient.platform.bigquery.BigQueryUtil;
                public class SalesTxnCanonicalProcessor {
                    private static final String TRANSACTION_HEADER_TABLE = "SalesTransactionHeader";
                    private BigQueryUtil bigQueryUtil;
                    public void processTransaction() {
                        bigQueryUtil.save(project, dataset, TRANSACTION_HEADER_TABLE, rows);
                    }
                }
                """;
        var files = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "SalesTxnCanonicalProcessor.java", java,
                "com.example.SalesTxnCanonicalProcessor"));

        List<FactBatch.DataAccessFact> facts = extractor.extract("acme", "svc-bq", files);

        assertThat(facts).singleElement().satisfies(f -> {
            assertThat(f.storeType()).isEqualTo("BIGQUERY");
            assertThat(f.tableOrEntity()).isEqualTo("SalesTransactionHeader");
            assertThat(f.operation()).isEqualTo("WRITE");
            assertThat(f.evidenceSource()).isEqualTo("BIGQUERY_DIRECT");
        });
    }
}
