package io.testseer.backend.ingestion.catalog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StaticImportIndexTest {

    @Test
    void build_wildcardAndSingleFieldStaticImports() {
        String source = """
                package com.example;
                import static com.example.util.Constants.*;
                import static com.example.other.Paths.SINGLE_PATH;
                class X {}
                """;
        StaticImportIndex index = StaticImportIndex.build(source);

        assertThat(index.resolveStaticFieldFqn("SINGLE_PATH"))
                .isEqualTo("com.example.other.Paths#SINGLE_PATH");
        assertThat(index.wildcardStaticTypes()).containsExactly("com.example.util.Constants");
    }
}
