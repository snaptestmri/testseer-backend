package io.testseer.backend.admin;

import java.util.List;

public record MavenLinkBackfillResponse(
        String orgId,
        int servicesProcessed,
        int dependencyRowsUpdated,
        int ownedByEdgesSynced,
        List<ServiceBackfillSummary> services
) {
    public record ServiceBackfillSummary(
            String serviceId,
            String commitSha,
            int rowsUpdated,
            int ownedByEdges
    ) {}
}
