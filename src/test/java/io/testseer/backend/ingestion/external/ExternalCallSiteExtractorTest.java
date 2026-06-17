package io.testseer.backend.ingestion.external;

import io.testseer.backend.ingestion.ParsedModel;
import io.testseer.backend.ingestion.messaging.MessagingFactOrchestrator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;

class ExternalCallSiteExtractorTest {

    private final ExternalCallSiteExtractor extractor = new ExternalCallSiteExtractor();

    @Test
    void extractCallSites_toleratesNullContentAndKotlinPaths() {
        List<MessagingFactOrchestrator.SourceFile> sources = List.of(
                source("Broken.java", null),
                source("MongoRepo.kt", "package com.example\nclass MongoRepo"),
                source("Valid.java", """
                        package com.example;
                        public class Valid {
                            public void m() {}
                        }
                        """)
        );

        assertThatCode(() -> extractor.extractCallSites(sources)).doesNotThrowAnyException();
        assertThatCode(() -> extractor.extractConfigBindings(sources)).doesNotThrowAnyException();
    }

    private static MessagingFactOrchestrator.SourceFile source(String path, String content) {
        return new MessagingFactOrchestrator.SourceFile(
                path,
                content,
                ParsedModel.of(path, null, List.of(), List.of(), List.of(),
                        List.of(), List.of(), false, null, null, List.of(), List.of())
        );
    }
}
