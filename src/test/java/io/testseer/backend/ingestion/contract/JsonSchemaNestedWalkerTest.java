package io.testseer.backend.ingestion.contract;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonSchemaNestedWalkerTest {

    private final JsonSchemaNestedWalker walker = new JsonSchemaNestedWalker();

    @Test
    void walk_nestedObjectAndArrayPaths() {
        Map<String, Object> addressSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "city", Map.of("type", "string"),
                        "zip", Map.of("type", "string")
                )
        );
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", Map.of(
                "offerIds", Map.of("type", "array", "items", Map.of("type", "string")),
                "address", addressSchema
        ));

        List<String> paths = walker.walk("RedeemRequest", root, Map.of("RedeemRequest", root));

        assertThat(paths).contains("offerIds", "offerIds[]", "address", "address.city", "address.zip");
    }
}
