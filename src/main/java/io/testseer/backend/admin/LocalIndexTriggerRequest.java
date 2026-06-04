package io.testseer.backend.admin;

import jakarta.validation.constraints.NotBlank;

public record LocalIndexTriggerRequest(
        @NotBlank String orgId,
        @NotBlank String path
) {}
