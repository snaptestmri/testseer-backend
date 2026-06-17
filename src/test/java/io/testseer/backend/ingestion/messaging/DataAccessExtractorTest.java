package io.testseer.backend.ingestion.messaging;

import io.testseer.backend.ingestion.FactBatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DataAccessExtractorTest {

    private final DataAccessExtractor extractor = new DataAccessExtractor();

    @Test
    void extract_findsRepositoryWrite() {
        String java = """
                package com.example;
                public class PartnerOfferCallRecorder {
                  public void record() {
                    partnerOfferCallRepo.save(entity);
                  }
                }
                """;
        var files = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "PartnerOfferCallRecorder.java", java, "com.example.PartnerOfferCallRecorder"));

        List<FactBatch.DataAccessFact> facts = extractor.extract(files);

        assertThat(facts).anyMatch(f ->
                "WRITE".equals(f.operation())
                        && f.repositoryFqn().equals("partnerOfferCallRepo")
                        && f.tableOrEntity().equals("partner_offer_call"));
    }

    @Test
    void extract_findsReadWithTableAnnotation() {
        String java = """
                package com.example;
                @Table(name = "notification_tracking")
                public class NotificationTracker {
                  public void poll() {
                    trackingRepo.findByOfferId(offerId);
                  }
                }
                """;
        var files = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "NotificationTracker.java", java, "com.example.NotificationTracker"));

        List<FactBatch.DataAccessFact> facts = extractor.extract(files);

        assertThat(facts).anyMatch(f ->
                "READ".equals(f.operation())
                        && "notification_tracking".equals(f.tableOrEntity()));
    }
}
