package io.testseer.backend.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ParsedModelChunkingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void partitionModelsBySerializedSize_splitsWhenChunkWouldExceedTarget() throws Exception {
        String largeJavadoc = "x".repeat(DualWriteService.PARSED_MODELS_CHUNK_TARGET_CHARS);
        ParsedModel large = ParsedModel.of(
                "Large.java", "com.example.Large",
                List.of(), List.of(), List.of(), List.of(), List.of(),
                false, null, largeJavadoc, List.of(), List.of());
        ParsedModel small = ParsedModel.of(
                "Small.java", "com.example.Small",
                List.of(), List.of(), List.of(), List.of(), List.of(),
                false, null, null, List.of(), List.of());

        List<List<ParsedModel>> chunks = DualWriteService.partitionModelsBySerializedSize(
                List.of(large, small), mapper);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).containsExactly(large);
        assertThat(chunks.get(1)).containsExactly(small);
    }

    @Test
    void partitionModelsBySerializedSize_keepsSmallReposInSingleChunk() throws Exception {
        List<ParsedModel> models = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            models.add(ParsedModel.of(
                    "C" + i + ".java", "com.example.C" + i,
                    List.of(), List.of(), List.of(), List.of(), List.of(),
                    false, null, null, List.of(), List.of()));
        }

        List<List<ParsedModel>> chunks = DualWriteService.partitionModelsBySerializedSize(models, mapper);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).hasSize(5);
    }
}
