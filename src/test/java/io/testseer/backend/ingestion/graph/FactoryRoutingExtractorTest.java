package io.testseer.backend.ingestion.graph;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import io.testseer.backend.ingestion.ParsedModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FactoryRoutingExtractorTest {

  @Test
  void extractsPostConstructMapRouting() {
    String source = """
        package com.example.eval.processors;

        import javax.annotation.PostConstruct;
        import javax.annotation.Resource;

        public class ProcessorFactory {

            @Resource(name = "defaultTxnEvalProcessor")
            private TxnEvalProcessor defaultTxnEvalProcessor;

            @Resource(name = "receiptTxnEvalProcessor")
            private TxnEvalProcessor receiptTxnEvalProcessor;

            @PostConstruct
            void setUpMap() {
                java.util.Map<TransactionSource, TxnEvalProcessor> txnSourceToProcessorMap = new java.util.HashMap<>();
                txnSourceToProcessorMap.put(TransactionSource.RECEIPT_EXTRACTION_CONSUMER, receiptTxnEvalProcessor);
                txnSourceToProcessorMap.put(TransactionSource.BI_SALES_TRANSACTION, defaultTxnEvalProcessor);
            }

            public TxnEvalProcessor getTxnEvalProcessorByTxnSource(SalesTransactionEvent event) {
                return java.util.Optional.ofNullable(txnSourceToProcessorMap.get(event.getTransactionSource()))
                        .orElse(defaultTxnEvalProcessor);
            }
        }
        """;

    ClassOrInterfaceDeclaration cls = parseClass(source);
    List<ParsedModel.FieldInjectionDef> fields =
        MethodCallGraphExtractor.extractFieldInjections(cls);

    Map<String, String> beanIndex = Map.of(
        "defaultTxnEvalProcessor", "com.example.eval.processors.DefaultTxnEvalProcessor",
        "receiptTxnEvalProcessor", "com.example.eval.processors.ReceiptTxnEvalProcessor");

    FactoryRoutingExtractor.BeanNameResolver resolver = new FactoryRoutingExtractor.BeanNameResolver() {
      @Override
      public String resolveBeanName(String beanName) {
        return beanIndex.get(beanName);
      }

      @Override
      public String resolveType(String typeName) {
        return typeName;
      }
    };

    List<ParsedModel.FactoryRoutingDef> routes = FactoryRoutingExtractor.extract(
        cls,
        "com.example.eval.processors.ProcessorFactory",
        fields,
        resolver);

    assertThat(routes).extracting(ParsedModel.FactoryRoutingDef::routingKey)
        .contains("RECEIPT_EXTRACTION_CONSUMER", "BI_SALES_TRANSACTION");
    assertThat(routes).anyMatch(r ->
        "receiptTxnEvalProcessor".equals(r.targetBean())
            && r.targetClassFqn().contains("ReceiptTxnEvalProcessor"));
    assertThat(routes).anyMatch(ParsedModel.FactoryRoutingDef::fallback);
  }

  private static ClassOrInterfaceDeclaration parseClass(String source) {
    ParseResult<CompilationUnit> parsed = new JavaParser().parse(source);
    return parsed.getResult().orElseThrow()
        .getTypes().get(0).asClassOrInterfaceDeclaration();
  }
}
