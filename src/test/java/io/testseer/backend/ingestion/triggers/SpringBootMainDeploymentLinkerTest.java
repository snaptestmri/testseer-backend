package io.testseer.backend.ingestion.triggers;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.messaging.YamlPubSubExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpringBootMainDeploymentLinkerTest {

    private final SpringBootMainDeploymentLinker linker = new SpringBootMainDeploymentLinker();

    @Test
    void enrichesSpringBootMainWithDeploymentName() {
        FactBatch.EntryTriggerFact main = new FactBatch.EntryTriggerFact(
                "spring-boot:com.example.App",
                "SPRING_BOOT_MAIN",
                "INBOUND",
                "dev",
                "deploy",
                "INTERNAL",
                null,
                "/deploy/transaction-eval-consumer",
                "com.example.App",
                "main",
                null,
                "App.java",
                "JAVA_SPRING_BOOT_MAIN",
                0.85,
                "{\"role\":\"wiring_only\",\"moduleDir\":\"transaction-eval-consumer\"}"
        );

        String deploymentYaml = """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: transaction-eval-consumer
                spec:
                  replicas: 1
                """;

        List<FactBatch.EntryTriggerFact> linked = linker.link(
                List.of(main),
                List.of(new YamlPubSubExtractor.ConfigFile(
                        "kubernetes-manifests/dev/transaction-eval-consumer.deployment.yaml",
                        deploymentYaml)));

        assertThat(linked).hasSize(1);
        assertThat(linked.get(0).pathPattern()).isEqualTo("/deploy/transaction-eval-consumer");
        assertThat(linked.get(0).evidenceSource()).contains("K8S_DEPLOYMENT");
        assertThat(linked.get(0).attributes()).contains("deploymentName");
    }
}
