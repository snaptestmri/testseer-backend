package io.testseer.backend.ingestion;

import java.util.List;

public record FactBatch(
        String jobId,
        String orgId,
        String repo,
        String serviceId,
        String commitSha,
        String snapshotType,       // "BASELINE" | "DELTA"
        List<SymbolFact> symbolFacts,
        List<OutboundCallFact> outboundCallFacts,
        List<PeripheralFact> peripheralFacts,
        List<UnsupportedConstructFact> unsupportedConstructFacts
) {
    public record SymbolFact(
            String filePath, String symbolFqn, String symbolKind,
            String attributes, String evidenceSource, double confidence
    ) {}

    public record OutboundCallFact(
            String sourceSymbol, String httpMethod, String path,
            String evidenceSource, double confidence
    ) {}

    public record PeripheralFact(
            String peripheralType, int detectionTier,
            String detectionSignals, String prerequisiteText, String reasonCode
    ) {}

    public record UnsupportedConstructFact(
            String filePath, String reasonCode, String detail
    ) {}
}
