package io.testseer.backend.admin;

import java.util.Map;

public record IndexClearRequest(
        String scope,
        String orgId,
        String serviceId,
        boolean includeRegistry
) {
    public IndexClearRequest {
        if (scope == null || scope.isBlank()) {
            scope = "SERVICE";
        }
    }
}
