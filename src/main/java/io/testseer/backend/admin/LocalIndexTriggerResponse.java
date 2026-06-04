package io.testseer.backend.admin;

public record LocalIndexTriggerResponse(
        String serviceId,
        String serviceName,
        String commitSha,
        int fileCount,
        boolean autoRegistered
) {}
