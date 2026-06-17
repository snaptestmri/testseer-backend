package io.testseer.backend.ingestion.maven;

import io.testseer.backend.config.ArtifactLinkRulePack;
import io.testseer.backend.config.ArtifactLinkRulePackLoader;
import io.testseer.backend.config.WorkspaceConfig;
import io.testseer.backend.config.workspace.OrgWorkspaceConfigResolver;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Maps internal {@code groupId:artifactId} coordinates to owning service registry rows. */
@Component
public class InternalArtifactLinker {

    public enum LinkSource {
        MAVEN_MODULE_GAV,
        CATALOG,
        REGISTRY,
        ALIAS
    }

    private static final double CONFIDENCE_GAV = 0.98;
    private static final double CONFIDENCE_CATALOG = 0.95;
    private static final double CONFIDENCE_ALIAS = 0.92;
    private static final double CONFIDENCE_REGISTRY = 0.90;

    private final ServiceRegistryRepository registryRepository;
    private final OrgWorkspaceConfigResolver workspaceConfigResolver;
    private final ArtifactLinkRulePackLoader rulePackLoader;
    private final JdbcClient db;

    public InternalArtifactLinker(
            ServiceRegistryRepository registryRepository,
            OrgWorkspaceConfigResolver workspaceConfigResolver,
            ArtifactLinkRulePackLoader rulePackLoader,
            JdbcClient db) {
        this.registryRepository = registryRepository;
        this.workspaceConfigResolver = workspaceConfigResolver;
        this.rulePackLoader = rulePackLoader;
        this.db = db;
    }

    public record ArtifactLink(String serviceId, String repo, LinkSource source, double confidence) {}

    public record LinkIndex(Map<String, ArtifactLink> entries) {
        static LinkIndex empty() {
            return new LinkIndex(Map.of());
        }
    }

    public LinkIndex buildLinkIndex(String orgId) {
        Map<String, ArtifactLink> entries = new HashMap<>();
        loadRegistryLinks(orgId, entries);
        loadCatalogLinks(orgId, entries);
        loadWorkspaceCatalogLinks(orgId, entries);
        loadMavenModuleGavLinks(orgId, entries);
        return new LinkIndex(Map.copyOf(entries));
    }

    public Optional<ArtifactLink> resolve(
            String orgId,
            String consumerServiceId,
            String groupId,
            String artifactId,
            LinkIndex index,
            Set<String> localModuleGavs) {

        if (artifactId == null || artifactId.isBlank()) {
            return Optional.empty();
        }
        if (localModuleGavs != null && groupId != null && localModuleGavs.contains(gavKey(groupId, artifactId))) {
            return Optional.empty();
        }

        Optional<ArtifactLink> match = lookup(index, groupId, artifactId);
        if (match.isEmpty()) {
            match = resolveAlias(groupId, artifactId, index);
        }
        return match.filter(link -> !link.serviceId().equals(consumerServiceId));
    }

    public static String gavKey(String groupId, String artifactId) {
        return groupId + ":" + artifactId;
    }

    private Optional<ArtifactLink> lookup(LinkIndex index, String groupId, String artifactId) {
        if (index == null || index.entries().isEmpty()) {
            return Optional.empty();
        }
        if (groupId != null && !groupId.isBlank()) {
            ArtifactLink byGav = index.entries().get(normalizeKey(gavKey(groupId, artifactId)));
            if (byGav != null) {
                return Optional.of(byGav);
            }
        }
        ArtifactLink byArtifact = index.entries().get(normalizeKey(artifactId));
        if (byArtifact != null) {
            return Optional.of(byArtifact);
        }
        return Optional.empty();
    }

    private Optional<ArtifactLink> resolveAlias(String groupId, String artifactId, LinkIndex index) {
        ArtifactLinkRulePack pack = rulePackLoader.getRulePack();
        Optional<String> catalogId = pack.catalogLibraryFor(groupId, artifactId);
        if (catalogId.isEmpty() || index == null) {
            return Optional.empty();
        }
        ArtifactLink catalogLink = index.entries().get(catalogId.get());
        if (catalogLink == null) {
            return Optional.empty();
        }
        return Optional.of(new ArtifactLink(
                catalogLink.serviceId(),
                catalogLink.repo(),
                LinkSource.ALIAS,
                CONFIDENCE_ALIAS));
    }

    private void loadRegistryLinks(String orgId, Map<String, ArtifactLink> entries) {
        for (ServiceEntry entry : registryRepository.findAll()) {
            if (!orgId.equals(entry.orgId())) {
                continue;
            }
            ArtifactLink link = new ArtifactLink(entry.serviceId(), entry.repo(), LinkSource.REGISTRY, CONFIDENCE_REGISTRY);
            putIfBetter(entries, entry.repo(), link);
            putIfBetter(entries, entry.serviceName(), link);
        }
    }

    private void loadCatalogLinks(String orgId, Map<String, ArtifactLink> entries) {
        List<CatalogRow> rows = db.sql("""
                SELECT library_id, repo, service_name
                FROM workspace_catalog_library
                WHERE org_id = :orgId
                """)
                .param("orgId", orgId)
                .query((rs, row) -> new CatalogRow(
                        rs.getString("library_id"),
                        rs.getString("repo"),
                        rs.getString("service_name")))
                .list();

        for (CatalogRow row : rows) {
            resolveCatalogEntry(orgId, row.libraryId(), row.repo(), row.serviceName())
                    .ifPresent(link -> {
                        putIfBetter(entries, row.libraryId(), link);
                        putIfBetter(entries, row.repo(), link);
                    });
        }
    }

    private void loadWorkspaceCatalogLinks(String orgId, Map<String, ArtifactLink> entries) {
        List<WorkspaceConfig.CatalogLibraryConfig> libs =
                workspaceConfigResolver.resolve(orgId).catalogLibraries();
        if (libs == null) {
            return;
        }
        for (WorkspaceConfig.CatalogLibraryConfig lib : libs) {
            resolveCatalogEntry(orgId, lib.id(), lib.repo(), lib.serviceName())
                    .ifPresent(link -> {
                        putIfBetter(entries, lib.id(), link);
                        putIfBetter(entries, lib.repo(), link);
                    });
        }
    }

    private Optional<ArtifactLink> resolveCatalogEntry(
            String orgId, String libraryId, String repo, String serviceName) {
        Optional<ServiceEntry> entry = Optional.empty();
        if (serviceName != null && !serviceName.isBlank() && repo != null) {
            entry = registryRepository.findByOrgRepoService(orgId, repo, serviceName);
        }
        if (entry.isEmpty() && serviceName != null && !serviceName.isBlank()) {
            entry = registryRepository.findAll().stream()
                    .filter(e -> orgId.equals(e.orgId()) && serviceName.equals(e.serviceName()))
                    .findFirst();
        }
        if (entry.isEmpty() && repo != null) {
            entry = registryRepository.findAll().stream()
                    .filter(e -> orgId.equals(e.orgId()) && repo.equals(e.repo()))
                    .findFirst();
        }
        return entry.map(e -> new ArtifactLink(e.serviceId(), e.repo(), LinkSource.CATALOG, CONFIDENCE_CATALOG));
    }

    private void loadMavenModuleGavLinks(String orgId, Map<String, ArtifactLink> entries) {
        List<GavRow> rows = db.sql("""
                SELECT DISTINCT ON (mm.group_id, mm.artifact_id)
                       mm.group_id, mm.artifact_id, mm.service_id, sr.repo
                FROM maven_module_facts mm
                JOIN service_registry sr ON sr.service_id = mm.service_id
                WHERE mm.org_id = :orgId
                  AND mm.group_id IS NOT NULL AND mm.artifact_id IS NOT NULL
                  AND (mm.packaging IS NULL OR mm.packaging IN ('jar', 'war', 'bundle'))
                ORDER BY mm.group_id, mm.artifact_id, mm.indexed_at DESC
                """)
                .param("orgId", orgId)
                .query((rs, row) -> new GavRow(
                        rs.getString("group_id"),
                        rs.getString("artifact_id"),
                        rs.getString("service_id"),
                        rs.getString("repo")))
                .list();

        for (GavRow row : rows) {
            ArtifactLink link = new ArtifactLink(
                    row.serviceId(), row.repo(), LinkSource.MAVEN_MODULE_GAV, CONFIDENCE_GAV);
            putIfBetter(entries, gavKey(row.groupId(), row.artifactId()), link);
            putIfBetter(entries, row.artifactId(), link);
        }
    }

    private static void putIfBetter(Map<String, ArtifactLink> entries, String key, ArtifactLink link) {
        if (key == null || key.isBlank() || link == null) {
            return;
        }
        String normalized = normalizeKey(key);
        ArtifactLink existing = entries.get(normalized);
        if (existing == null || link.confidence() >= existing.confidence()) {
            entries.put(normalized, link);
        }
    }

    private static String normalizeKey(String key) {
        return key.toLowerCase(Locale.ROOT);
    }

    private record CatalogRow(String libraryId, String repo, String serviceName) {}

    private record GavRow(String groupId, String artifactId, String serviceId, String repo) {}
}
