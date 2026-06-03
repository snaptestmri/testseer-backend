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

        if (model.classFqn() != null) {
            result.add(new FactBatch.SymbolFact(
                    model.filePath(), model.classFqn(), "CLASS",
                    toJson(Map.of("annotations", model.annotations())),
                    "javaparser", 1.0
            ));

            for (ParsedModel.EndpointDef ep : model.endpoints()) {
                String fqn = model.classFqn() + "#" + ep.methodName();
                result.add(new FactBatch.SymbolFact(
                        model.filePath(), fqn, "ENDPOINT",
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

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
