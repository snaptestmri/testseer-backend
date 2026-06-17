package io.testseer.backend.ingestion.testcalls;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.messaging.MessagingFactOrchestrator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class TestHttpCallOrchestrator {

    public List<FactBatch.TestHttpCallFact> buildFromSources(List<MessagingFactOrchestrator.SourceFile> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }

        List<String> contents = sources.stream()
                .map(MessagingFactOrchestrator.SourceFile::content)
                .filter(c -> c != null && !c.isBlank())
                .toList();
        Map<String, String> pathConstants = RestAssuredHttpCallExtractor.extractPathConstants(contents);

        Set<String> seen = new LinkedHashSet<>();
        List<FactBatch.TestHttpCallFact> facts = new ArrayList<>();

        for (MessagingFactOrchestrator.SourceFile source : sources) {
            if (source.content() == null || source.path() == null) {
                continue;
            }
            String sourceSymbol = source.parsedModel() != null ? source.parsedModel().classFqn() : null;
            for (RestAssuredHttpCallExtractor.ExtractedCall call :
                    RestAssuredHttpCallExtractor.extractFromFile(source.content(), pathConstants)) {
                String key = call.httpMethod() + "|" + call.pathNormalized() + "|" + source.path();
                if (!seen.add(key)) {
                    continue;
                }
                facts.add(new FactBatch.TestHttpCallFact(
                        source.path(),
                        sourceSymbol,
                        call.httpMethod(),
                        call.path(),
                        call.pathNormalized(),
                        call.pathConstantRef(),
                        call.evidenceSource(),
                        0.85
                ));
            }
        }
        return List.copyOf(facts);
    }
}
