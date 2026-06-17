package io.testseer.backend.ingestion.messaging;

import io.testseer.backend.ingestion.FactBatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DlqRetryPathExtractorTest {

    private final DlqRetryPathExtractor extractor = new DlqRetryPathExtractor();

    @Test
    void extract_parsesDlqDatasetAndTableFromRetryJobYaml() {
        String yaml = """
                spring:
                  cloud:
                    gcp:
                      bigquery:
                        dataset-name: PDN_DLQ_RETRY
                        table-name: ACTIVATE_OFFER_FREEDOM_INT_DLQ
                """;
        List<YamlPubSubExtractor.ConfigFile> files = List.of(
                new YamlPubSubExtractor.ConfigFile(
                        "optimus-pao-freedom-retry-job/src/main/resources/application-pdn.yaml", yaml));

        List<FactBatch.AsyncRetryPathFact> facts = extractor.extract(files);

        assertThat(facts).singleElement().satisfies(f -> {
            assertThat(f.envLane()).isEqualTo("pdn");
            assertThat(f.moduleName()).isEqualTo("optimus-pao-freedom-retry-job");
            assertThat(f.bqDataset()).isEqualTo("PDN_DLQ_RETRY");
            assertThat(f.bqTable()).isEqualTo("ACTIVATE_OFFER_FREEDOM_INT_DLQ");
            assertThat(f.evidenceSource()).isEqualTo("YAML_DLQ_RETRY");
        });
    }

    @Test
    void extract_skipsNonRetryJobPaths() {
        String yaml = """
                spring:
                  cloud:
                    gcp:
                      bigquery:
                        dataset-name: PDN_DLQ_RETRY
                        table-name: ACTIVATE_OFFER_FREEDOM_INT_DLQ
                """;
        List<YamlPubSubExtractor.ConfigFile> files = List.of(
                new YamlPubSubExtractor.ConfigFile("optimus-pao-ns/src/main/resources/application-pdn.yaml", yaml));

        assertThat(extractor.extract(files)).isEmpty();
    }
}
