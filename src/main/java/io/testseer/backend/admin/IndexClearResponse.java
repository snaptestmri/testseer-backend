package io.testseer.backend.admin;

import java.util.Map;

public record IndexClearResponse(
        String scope,
        String orgId,
        String serviceId,
        String serviceName,
        Map<String, Integer> deletedCounts
) {
    public int totalDeleted() {
        return deletedCounts.values().stream().mapToInt(Integer::intValue).sum();
    }
}
