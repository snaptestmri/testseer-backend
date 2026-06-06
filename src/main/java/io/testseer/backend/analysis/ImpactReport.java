package io.testseer.backend.analysis;

import java.util.List;

public record ImpactReport(
        String serviceId,
        String commitSha,
        List<ChangedSymbol> changedSymbols,
        List<AffectedConsumer> affectedConsumers,
        List<DownstreamDependency> downstreamDependencies,
        List<SuggestedTest> suggestedTestScope,
        List<String> missingTestClasses
) {
    public record ChangedSymbol(
            String symbolFqn,
            String symbolKind,
            String filePath,
            String httpMethod,
            String path
    ) {}

    public record AffectedConsumer(
            String source,
            String consumerServiceId,
            String consumerServiceName,
            String consumerClass,
            String nodeType,
            String httpMethod,
            String path
    ) {}

    public record DownstreamDependency(
            String callerClass,
            String httpMethod,
            String path
    ) {}

    public record SuggestedTest(
            String type,
            String className,
            String targetService,
            boolean exists,
            String reason
    ) {}
}
