package io.testseer.backend.analysis;

import java.util.List;

public record ImpactReport(
        String serviceId,
        String commitSha,
        List<ChangedSymbol> changedSymbols,
        List<AffectedConsumer> affectedConsumers,
        List<DownstreamDependency> downstreamDependencies,
        List<ExternalDependency> externalDownstreamDependencies,
        List<SuggestedTest> suggestedTestScope,
        List<String> missingTestClasses,
        List<ArtifactImpact> artifactImpact
) {
    public ImpactReport {
        if (artifactImpact == null) artifactImpact = List.of();
    }

    /** Backward-compatible factory when artifact impact is not under test. */
    public static ImpactReport withoutArtifactImpact(
            String serviceId,
            String commitSha,
            List<ChangedSymbol> changedSymbols,
            List<AffectedConsumer> affectedConsumers,
            List<DownstreamDependency> downstreamDependencies,
            List<ExternalDependency> externalDownstreamDependencies,
            List<SuggestedTest> suggestedTestScope,
            List<String> missingTestClasses) {
        return new ImpactReport(serviceId, commitSha, changedSymbols, affectedConsumers,
                downstreamDependencies, externalDownstreamDependencies, suggestedTestScope,
                missingTestClasses, List.of());
    }

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

    public record ExternalDependency(
            String callerClass,
            String endpointId,
            String partnerSlug,
            String operation,
            String httpMethod,
            String urlResolved,
            String configKey,
            String flowStep,
            String boundary
    ) {}

    public record SuggestedTest(
            String type,
            String className,
            String targetService,
            boolean exists,
            String reason
    ) {}

    public record ArtifactImpact(
            String artifact,
            String previousVersion,
            String newVersion,
            List<DownstreamArtifactService> downstreamServices
    ) {}

    public record DownstreamArtifactService(
            String serviceId,
            String repo,
            String pinnedVersion
    ) {}
}
