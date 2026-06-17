package io.testseer.backend.ingestion.catalog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceSourcesTest {

    @Test
    void append_addsTagToEmpty() {
        assertThat(EvidenceSources.append(null, "ENTITY_ANNOTATION"))
                .isEqualTo("ENTITY_ANNOTATION");
    }

    @Test
    void append_dedupesRepeatedTags() {
        assertThat(EvidenceSources.append("ENTITY_ANNOTATION+REPO_GENERIC", "REPO_GENERIC"))
                .isEqualTo("ENTITY_ANNOTATION+REPO_GENERIC");
    }

    @Test
    void append_chainsDistinctTags() {
        assertThat(EvidenceSources.append("ENTITY_ANNOTATION+REPO_GENERIC", "BQ_MIRROR"))
                .isEqualTo("ENTITY_ANNOTATION+REPO_GENERIC+BQ_MIRROR");
    }

    @Test
    void append_truncatesWhenExceedingMaxLength() {
        String longTag = "X".repeat(EvidenceSources.MAX_LENGTH);
        assertThat(EvidenceSources.append(null, longTag)).hasSize(EvidenceSources.MAX_LENGTH);
    }
}
