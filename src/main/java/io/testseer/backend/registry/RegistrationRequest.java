package io.testseer.backend.registry;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record RegistrationRequest(
        @NotBlank String orgId,
        @NotBlank String repo,
        @NotBlank String serviceName,
        @NotBlank String buildTool,
        String moduleType,
        List<String> sourceRoots,
        List<String> testRoots,
        String ownerTeam
) {}
