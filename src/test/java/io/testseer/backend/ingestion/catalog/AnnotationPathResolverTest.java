package io.testseer.backend.ingestion.catalog;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotationPathResolverTest {

    @Test
    void resolve_staticWildcardImport() {
        String constants = """
                package com.quotient.platform.userprofile.util;
                public class Constants {
                    public static final String USER_OFFER_TRANSACTION_API_URL = "/offer/transaction/history";
                }
                """;
        StringConstantIndex index = StringConstantIndex.build(Map.of("Constants.java", constants));
        StaticImportIndex staticImports = StaticImportIndex.build(
                "import static com.quotient.platform.userprofile.util.Constants.*;");
        ImportIndex typeImports = ImportIndex.build("package com.example;");

        var resolved = AnnotationPathResolver.resolve(
                new com.github.javaparser.ast.expr.NameExpr("USER_OFFER_TRANSACTION_API_URL"),
                index,
                staticImports,
                typeImports);

        assertThat(resolved.kind()).isEqualTo(AnnotationPathResolver.ResolutionKind.FIELD);
        assertThat(resolved.path()).isEqualTo("/offer/transaction/history");
        assertThat(resolved.fieldFqn()).isEqualTo(
                "com.quotient.platform.userprofile.util.Constants#USER_OFFER_TRANSACTION_API_URL");
    }

    @Test
    void resolve_qualifiedFieldAccess() {
        String constants = """
                package com.example.util;
                public class Constants {
                    public static final String API = "/api";
                }
                """;
        StringConstantIndex index = StringConstantIndex.build(Map.of("Constants.java", constants));
        ImportIndex typeImports = ImportIndex.build("""
                package com.example.web;
                import com.example.util.Constants;
                """);

        var expr = new com.github.javaparser.ast.expr.FieldAccessExpr(
                new com.github.javaparser.ast.expr.NameExpr("Constants"), "API");
        var resolved = AnnotationPathResolver.resolve(
                expr, index, StaticImportIndex.build(""), typeImports);

        assertThat(resolved.path()).isEqualTo("/api");
    }
}
