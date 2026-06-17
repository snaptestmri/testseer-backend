package io.testseer.backend.ingestion.triggers;

import io.testseer.backend.config.TriggerRulePack;
import io.testseer.backend.config.TriggerRulePackLoader;
import io.testseer.backend.ingestion.FactBatch;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AirflowDagTriggerExtractorTest {

    private final AirflowDagTriggerExtractor extractor = new AirflowDagTriggerExtractor();
    private final TriggerRulePack rulePack = new TriggerRulePackLoader(
            new FileSystemResource("../config/rule-packs/quotient-triggers.yml"))
            .getRulePack();

    @Test
    void extract_rulePackSeedsAirflowTriggers_forLinkedService() {
        List<FactBatch.EntryTriggerFact> facts = extractor.extract(
                Map.of(), rulePack, "pdn", "riq-parquet-file-ingestion-job", "riq-parquet-file-ingestion-job");

        assertThat(facts).anyMatch(f ->
                "AIRFLOW_DAG".equals(f.triggerKind())
                        && "offer_parquet_ingestion".equals(extractAttr(f, "dagId"))
                        && "trigger_dataflow".equals(extractAttr(f, "taskId")));
        assertThat(facts).hasSize(1);
    }

    @Test
    void extract_rulePackSkipsUnlinkedService() {
        List<FactBatch.EntryTriggerFact> facts = extractor.extract(
                Map.of(), rulePack, "pdn", "optimus-offer-services-suite", "optimus-offer-services-suite");

        assertThat(facts).isEmpty();
    }

    @Test
    void extract_rulePackSeedsAirflowTriggers_legacyOverload() {
        List<FactBatch.EntryTriggerFact> facts = extractor.extract(Map.of(), rulePack, "pdn");

        assertThat(facts).isEmpty();
    }

    @Test
    void extract_parsesDagPythonFile() {
        String dag = """
                from airflow import DAG
                dag = DAG('sample_offer_sync', schedule='@daily')
                task = PythonOperator(task_id='run_ingest', python_callable=lambda: None)
                """;
        List<FactBatch.EntryTriggerFact> facts = extractor.extract(
                Map.of("dags/sample_offer_sync.py", dag), TriggerRulePack.empty(), "pdn");

        assertThat(facts).anyMatch(f ->
                f.triggerId().equals("airflow:sample_offer_sync:run_ingest")
                        && "AIRFLOW_DAG_PARSE".equals(f.evidenceSource()));
    }

    private static String extractAttr(FactBatch.EntryTriggerFact fact, String key) {
        if (fact.attributes() == null) return null;
        String needle = "\"" + key + "\":\"";
        int start = fact.attributes().indexOf(needle);
        if (start < 0) return null;
        int valueStart = start + needle.length();
        int valueEnd = fact.attributes().indexOf('"', valueStart);
        return fact.attributes().substring(valueStart, valueEnd);
    }
}
