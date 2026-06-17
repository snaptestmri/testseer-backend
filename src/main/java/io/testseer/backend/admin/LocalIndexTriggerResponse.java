package io.testseer.backend.admin;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result of a local folder indexing operation")
public record LocalIndexTriggerResponse(
        @Schema(description = "Stable service identifier (UUID) assigned at registration",
                example = "a3f2e1b4-9c8d-4e2f-b1a3-7d6e5f4c3b2a")
        String serviceId,

        @Schema(description = "Service name derived from the directory name", example = "orders")
        String serviceName,

        @Schema(description = "Git commit SHA at the indexed directory, or 'local-{epoch}' if not a git repo",
                example = "d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3")
        String commitSha,

        @Schema(description = "Number of .java files found and submitted for analysis", example = "47")
        int fileCount,

        @Schema(description = "Number of YAML application config and .proto files submitted for messaging analysis",
                example = "31")
        int configFileCount,

        @Schema(description = "Number of OpenAPI/JSON spec files submitted (catalog libraries with indexOpenApi)",
                example = "298")
        int openApiFileCount,

        @Schema(description = "Number of DDL/SQL files submitted (catalog libraries with indexDdl)", example = "12")
        int ddlFileCount,

        @Schema(description = "True if the service was newly registered during this request; false if it already existed",
                example = "true")
        boolean autoRegistered
) {}
