package io.testseer.backend.ingestion.triggers;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComponentScanParserTest {

    @Test
    void resolvesBasePackageClassesFromImports() {
        String source = """
                package com.quotient.platform.transaction.eval;
                import com.quotient.api.config.ApiConfig;
                import com.quotient.platform.msg.consumer.config.salesspipeline.TransactionEvalConsumerConfig;
                import org.springframework.context.annotation.ComponentScan;
                @ComponentScan(basePackageClasses = {
                    ApiConfig.class,
                    TransactionEvalConsumerConfig.class
                })
                public class TransactionEvaluationKCApplication {}
                """;

        List<String> targets = ComponentScanParser.resolveScanTargetFqns(
                source, "com.quotient.platform.transaction.eval.TransactionEvaluationKCApplication");

        assertThat(targets).containsExactly(
                "com.quotient.api.config.ApiConfig",
                "com.quotient.platform.msg.consumer.config.salesspipeline.TransactionEvalConsumerConfig");
    }
}
