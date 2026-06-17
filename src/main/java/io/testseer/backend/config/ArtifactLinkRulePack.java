package io.testseer.backend.config;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public record ArtifactLinkRulePack(List<ArtifactAliasRule> artifactLinks) {

    public record ArtifactAliasRule(String groupId, String artifactId, String catalogLibrary) {}

    public static ArtifactLinkRulePack empty() {
        return new ArtifactLinkRulePack(List.of());
    }

    public Optional<String> catalogLibraryFor(String groupId, String artifactId) {
        if (artifactLinks == null || artifactId == null || artifactId.isBlank()) {
            return Optional.empty();
        }
        String gid = groupId != null ? groupId : "";
        for (ArtifactAliasRule rule : artifactLinks) {
            if (rule.artifactId() == null) {
                continue;
            }
            if (!rule.artifactId().equalsIgnoreCase(artifactId)) {
                continue;
            }
            if (rule.groupId() != null && !rule.groupId().isBlank()
                    && !rule.groupId().equalsIgnoreCase(gid)) {
                continue;
            }
            if (rule.catalogLibrary() != null && !rule.catalogLibrary().isBlank()) {
                return Optional.of(rule.catalogLibrary().toLowerCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }
}
