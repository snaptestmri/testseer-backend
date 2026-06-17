package io.testseer.backend.admin;

import io.swagger.v3.oas.annotations.media.Schema;

public record MavenLinkBackfillRequest(
        @Schema(description = "Org scope; resolved from service registry when omitted")
        String orgId,
        @Schema(description = "Optional single service; when omitted all services with maven facts in org")
        String serviceId,
        @Schema(description = "When true, upsert OWNED_BY graph edges for cross-repo links")
        boolean syncOwnedByEdges
) {
    public static MavenLinkBackfillRequest forService(String orgId, String serviceId) {
        return new MavenLinkBackfillRequest(orgId, serviceId, true);
    }
}
