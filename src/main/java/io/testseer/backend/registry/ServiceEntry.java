package io.testseer.backend.registry;

import java.time.Instant;
import java.util.List;

public record ServiceEntry(
        String serviceId,
        String orgId,
        String repo,
        String serviceName,
        String moduleType,
        String buildTool,
        List<String> sourceRoots,
        List<String> testRoots,
        String ownerTeam,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {}
