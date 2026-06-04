package io.testseer.backend.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request to index all Java files in a server-accessible directory")
public record LocalIndexTriggerRequest(
        @Schema(description = "GitHub organisation identifier", example = "acme")
        @NotBlank String orgId,

        @Schema(description = "Absolute path on the server filesystem to the project root",
                example = "/workspace/orders")
        @NotBlank String path
) {}
