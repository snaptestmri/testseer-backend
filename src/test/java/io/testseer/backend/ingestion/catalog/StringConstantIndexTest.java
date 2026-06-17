package io.testseer.backend.ingestion.catalog;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StringConstantIndexTest {

    @Test
    void build_indexesPublicStaticFinalStringFields() {
        String constants = """
                package com.example.util;
                public class Constants {
                    public static final String USER_OFFER_TRANSACTION_API_URL = "/offer/transaction/history";
                    private static final String INTERNAL = "/internal";
                    public static final int COUNT = 3;
                }
                """;
        StringConstantIndex index = StringConstantIndex.build(Map.of("Constants.java", constants));

        assertThat(index.resolveField(
                "com.example.util.Constants#USER_OFFER_TRANSACTION_API_URL"))
                .contains("/offer/transaction/history");
        assertThat(index.resolveField("com.example.util.Constants#INTERNAL"))
                .contains("/internal");
        assertThat(index.resolveField("com.example.util.Constants#COUNT")).isEmpty();
    }
}
