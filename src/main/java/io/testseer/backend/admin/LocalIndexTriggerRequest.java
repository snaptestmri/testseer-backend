package io.testseer.backend.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request to index all Java files in a server-accessible directory")
public record LocalIndexTriggerRequest(
        @Schema(description = "GitHub organisation identifier", example = "acme")
        @NotBlank String orgId,

        @Schema(description = "Absolute path on the server filesystem to the project root",
                example = "/workspace/orders")
        @NotBlank String path,

        @Schema(description = "Optional workspace.yml catalogLibraries id when multiple libs share one repo",
                example = "platform-bigquery")
        String catalogLibraryId,

        @Schema(description = "Optional workspace.yml serviceModules id for monorepo index targets",
                example = "partner-adapter-suite")
        String serviceModuleId,

        @Schema(description = "Override Maven dependency:tree resolution for this index. "
                + "When omitted and bulkIndex is true, uses testseer.maven.bulk-index-tree-resolution-enabled.")
        Boolean mavenTreeResolution,

        @Schema(description = "When true (e.g. index-all-repos.sh), applies bulk Maven tree defaults "
                + "unless mavenTreeResolution is set explicitly.")
        Boolean bulkIndex
) {
}
