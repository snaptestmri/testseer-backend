package io.testseer.backend.admin;

public record IndexTriggerResponse(
        String jobId,
        String serviceId,
        String commitSha,
        int fileCount
) {}
