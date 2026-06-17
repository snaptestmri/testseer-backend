package io.testseer.backend.ingestion.graph;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import io.testseer.backend.ingestion.ParsedModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MethodCallGraphExtractorTest {

    @Test
    void extractFieldInjections_indexesAllArgsConstructorFields() {
        CompilationUnit cu = StaticJavaParser.parse("""
                package com.example;
                import lombok.AllArgsConstructor;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                @AllArgsConstructor(onConstructor_ = {@Autowired})
                public class HistoryController {
                    private final OrderService orderService;
                    private HistoryHelper historyHelper;
                }
                class OrderService {}
                class HistoryHelper {}
                """);

        ClassOrInterfaceDeclaration cls = cu.getTypes().stream()
                .filter(t -> t instanceof ClassOrInterfaceDeclaration)
                .map(t -> (ClassOrInterfaceDeclaration) t)
                .findFirst()
                .orElseThrow();
        List<ParsedModel.FieldInjectionDef> fields =
                MethodCallGraphExtractor.extractFieldInjections(cls);

        assertThat(fields).extracting(ParsedModel.FieldInjectionDef::variableName)
                .containsExactlyInAnyOrder("orderService", "historyHelper");
        assertThat(fields).allMatch(f -> "LOMBOK_CONSTRUCTOR".equals(f.injectionAnnotation()));
    }

    @Test
    void extractFieldInjections_requiredArgsConstructor_indexesFinalFieldsOnly() {
        CompilationUnit cu = StaticJavaParser.parse("""
                package com.example;
                import lombok.RequiredArgsConstructor;
                @RequiredArgsConstructor
                public class PaymentController {
                    private final PaymentService paymentService;
                    private AuditHelper auditHelper;
                }
                class PaymentService {}
                class AuditHelper {}
                """);

        ClassOrInterfaceDeclaration cls = cu.getTypes().stream()
                .filter(t -> t instanceof ClassOrInterfaceDeclaration)
                .map(t -> (ClassOrInterfaceDeclaration) t)
                .findFirst()
                .orElseThrow();
        List<ParsedModel.FieldInjectionDef> fields =
                MethodCallGraphExtractor.extractFieldInjections(cls);

        assertThat(fields).extracting(ParsedModel.FieldInjectionDef::variableName)
                .containsExactly("paymentService");
    }

    @Test
    void extract_resolvesMethodCallsOnLombokInjectedFields() {
        CompilationUnit cu = StaticJavaParser.parse("""
                package com.example;
                import lombok.AllArgsConstructor;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                @AllArgsConstructor
                public class HistoryController {
                    private final ShoppingHistoryServiceImpl shoppingHistoryServiceImpl;
                    private ShoppingHistoryHelper shoppingHistoryHelper;

                    public void fetchUserProfileShoppingHistory() {
                        shoppingHistoryHelper.validate();
                        shoppingHistoryServiceImpl.load();
                    }
                }
                class ShoppingHistoryServiceImpl { void load() {} }
                class ShoppingHistoryHelper { void validate() {} }
                """);

        ClassOrInterfaceDeclaration cls = cu.getTypes().stream()
                .filter(t -> t instanceof ClassOrInterfaceDeclaration)
                .map(t -> (ClassOrInterfaceDeclaration) t)
                .findFirst()
                .orElseThrow();
        String fqn = "com.example.HistoryController";
        List<ParsedModel.FieldInjectionDef> injections =
                MethodCallGraphExtractor.extractFieldInjections(cls);
        List<ParsedModel.MethodCallDef> calls = MethodCallGraphExtractor.extract(
                cls, fqn, injections, type -> type);

        assertThat(calls).extracting(ParsedModel.MethodCallDef::calleeVariable)
                .contains("shoppingHistoryServiceImpl", "shoppingHistoryHelper");
        assertThat(calls).extracting(ParsedModel.MethodCallDef::calleeMethod)
                .contains("load", "validate");
    }
}
