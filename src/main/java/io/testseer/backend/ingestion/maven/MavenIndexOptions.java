package io.testseer.backend.ingestion.maven;

import io.testseer.backend.config.MavenProperties;

import java.util.List;

/** Per-index Maven extraction options (local index / worker). */
public record MavenIndexOptions(
        List<String> pomRoots,
        boolean treeResolutionEnabled
) {
    public MavenIndexOptions {
        pomRoots = pomRoots != null ? List.copyOf(pomRoots) : List.of();
    }

    public static MavenIndexOptions defaults(MavenProperties properties) {
        return new MavenIndexOptions(List.of(), properties.isTreeResolutionEnabled());
    }

    public static MavenIndexOptions bulkDefaults(MavenProperties properties) {
        boolean tree = properties.isBulkIndexTreeResolutionEnabled();
        return new MavenIndexOptions(List.of(), tree);
    }
}
