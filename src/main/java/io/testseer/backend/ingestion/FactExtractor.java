package io.testseer.backend.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class FactExtractor {

    private final ObjectMapper mapper = new ObjectMapper();

    public List<FactBatch.SymbolFact> extractSymbolFacts(ParsedModel model) {
        List<FactBatch.SymbolFact> result = new ArrayList<>();
        String filePath = effectiveFilePath(model);

        if (model.classFqn() != null) {
            result.add(new FactBatch.SymbolFact(
                    filePath, model.classFqn(), "CLASS",
                    toJson(Map.of("annotations", model.annotations())),
                    "javaparser", 1.0
            ));

            for (ParsedModel.EndpointDef ep : model.endpoints()) {
                String fqn = model.classFqn() + "#" + ep.methodName();
                result.add(new FactBatch.SymbolFact(
                        filePath, fqn, "ENDPOINT",
                        toJson(Map.of("httpMethod", ep.httpMethod(), "path", ep.path())),
                        "javaparser", 1.0
                ));
            }
        }
        return result;
    }

    public List<FactBatch.OutboundCallFact> extractOutboundCallFacts(ParsedModel model) {
        return model.outboundCalls().stream()
                .map(call -> new FactBatch.OutboundCallFact(
                        model.classFqn() != null ? model.classFqn() : model.filePath(),
                        call.httpMethod(),
                        call.path(),
                        "javaparser",
                        0.9
                ))
                .toList();
    }

    public List<FactBatch.UnsupportedConstructFact> extractUnsupportedConstructFacts(
            ParsedModel model) {
        if (!model.parseError()) return List.of();
        return List.of(new FactBatch.UnsupportedConstructFact(
                model.filePath(), "PARSE_ERROR", model.parseErrorDetail()
        ));
    }

    public List<FactBatch.SymbolFact> extractMethodFacts(ParsedModel model) {
        if (model.classFqn() == null || model.publicMethods().isEmpty()) return List.of();

        return model.publicMethods().stream()
                .map(m -> new FactBatch.SymbolFact(
                        effectiveFilePath(model),
                        model.classFqn() + "#" + m.name(),
                        "METHOD",
                        toJson(Map.of(
                                "returnType",       m.returnType(),
                                "parameterTypes",   m.parameterTypes(),
                                "thrownExceptions", m.thrownExceptions(),
                                "javadoc",          m.javadoc() != null ? m.javadoc() : ""
                        )),
                        "javaparser",
                        1.0
                ))
                .toList();
    }

    public List<FactBatch.SymbolFact> extractEnumFacts(ParsedModel model) {
        if (model.classFqn() == null || model.enumValues().isEmpty()) return List.of();

        return List.of(new FactBatch.SymbolFact(
                effectiveFilePath(model),
                model.classFqn(),
                "ENUM",
                toJson(Map.of(
                        "enumValues", model.enumValues(),
                        "javadoc",    model.classJavadoc() != null ? model.classJavadoc() : ""
                )),
                "javaparser",
                1.0
        ));
    }

    private String effectiveFilePath(ParsedModel model) {
        if (model.filePath() != null && !model.filePath().isBlank()) {
            return model.filePath().replace('\\', '/');
        }
        if (model.classFqn() != null) {
            return model.classFqn().replace('.', '/') + ".java";
        }
        return "unknown.java";
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
