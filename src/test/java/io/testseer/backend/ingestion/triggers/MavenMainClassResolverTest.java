package io.testseer.backend.ingestion.triggers;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MavenMainClassResolverTest {

    @Test
    void extractsMainClassFromPom() {
        String pom = """
                <project>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>spring-boot-maven-plugin</artifactId>
                        <configuration>
                          <mainClass>com.quotient.platform.transaction.eval.TransactionEvaluationKCApplication</mainClass>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """;

        Map<String, String> hints = MavenMainClassResolver.resolveFromContentByPath(Map.of(
                "evaluation-consumers/transaction-eval-consumer/pom.xml", pom));

        assertThat(hints)
                .containsEntry(
                        "com.quotient.platform.transaction.eval.TransactionEvaluationKCApplication",
                        "transaction-eval-consumer")
                .containsEntry(
                        "transaction-eval-consumer",
                        "com.quotient.platform.transaction.eval.TransactionEvaluationKCApplication");
    }
}
