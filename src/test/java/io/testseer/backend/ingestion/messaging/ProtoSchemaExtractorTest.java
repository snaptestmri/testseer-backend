package io.testseer.backend.ingestion.messaging;

import io.testseer.backend.ingestion.FactBatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProtoSchemaExtractorTest {

    private final ProtoSchemaExtractor extractor = new ProtoSchemaExtractor();

    private static final String PROTO = """
            syntax = "proto3";
            package com.example.events;
            option java_package = "com.example.events";
            option java_outer_classname = "OfferEvents";
            message OfferUpdateEvent {
              string offer_id = 1;
              repeated string partner_ids = 2;
            }
            """;

    @Test
    void extractCatalog_parsesMessageFields() {
        var files = List.of(new YamlPubSubExtractor.ConfigFile("src/main/resources/OfferUpdate.proto", PROTO));
        Map<String, ProtoSchemaExtractor.ProtoMessage> catalog = extractor.extractCatalog(files);

        assertThat(catalog).containsKey("OfferUpdateEvent");
        assertThat(catalog.get("OfferUpdateEvent").fields()).hasSize(2);
        assertThat(catalog.get("OfferUpdateEvent").javaFqn()).contains("OfferEvents");
    }

    @Test
    void extractFromJava_findsUnpackCall() {
        var catalog = extractor.extractCatalog(List.of(
                new YamlPubSubExtractor.ConfigFile("OfferUpdate.proto", PROTO)));
        String java = """
                package com.example;
                public class OfferConsumer {
                  public void onMessage(QMsgEvent event) {
                    var payload = event.getPayload().unpack(OfferEvents.OfferUpdateEvent.class);
                  }
                }
                """;
        var javaFiles = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                "OfferConsumer.java", java, "com.example.OfferConsumer"));

        List<FactBatch.MessageSchemaFact> facts = extractor.extractFromJava(javaFiles, catalog);

        assertThat(facts).anyMatch(f ->
                "INBOUND".equals(f.direction())
                        && f.unpackExpression().contains("OfferUpdateEvent")
                        && f.linkedClassFqn().equals("com.example.OfferConsumer"));
    }
}
