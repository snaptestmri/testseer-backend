package io.testseer.backend.ingestion.maven;

import io.testseer.backend.admin.MavenLinkBackfillRequest;
import io.testseer.backend.admin.MavenLinkBackfillResponse;
import io.testseer.backend.graph.MavenGraphProjector;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class MavenLinkBackfillService {

    private final InternalArtifactLinker artifactLinker;
    private final MavenGraphProjector mavenGraphProjector;
    private final ServiceRegistryRepository registryRepository;
    private final JdbcClient db;

    public MavenLinkBackfillService(
            InternalArtifactLinker artifactLinker,
            MavenGraphProjector mavenGraphProjector,
            ServiceRegistryRepository registryRepository,
            JdbcClient db) {
        this.artifactLinker = artifactLinker;
        this.mavenGraphProjector = mavenGraphProjector;
        this.registryRepository = registryRepository;
        this.db = db;
    }

    @Transactional
    public MavenLinkBackfillResponse backfill(MavenLinkBackfillRequest request) {
        String orgId = resolveOrgId(request.orgId(), request.serviceId());
        List<String> serviceIds = resolveServiceIds(orgId, request.serviceId());
        InternalArtifactLinker.LinkIndex linkIndex = artifactLinker.buildLinkIndex(orgId);

        int totalUpdated = 0;
        int totalOwnedBy = 0;
        List<MavenLinkBackfillResponse.ServiceBackfillSummary> summaries = new ArrayList<>();

        for (String serviceId : serviceIds) {
            Optional<ServiceSnapshot> snapshot = loadLatestSnapshot(serviceId);
            if (snapshot.isEmpty()) {
                continue;
            }
            ServiceSnapshot snap = snapshot.get();
            Set<String> localGavs = loadLocalModuleGavs(serviceId, snap.commitSha());
            List<DependencyRow> rows = loadDependencyRows(serviceId, snap.commitSha());

            int rowsUpdated = 0;
            List<FactBatch.MavenDependencyFact> linkedDeps = new ArrayList<>();
            for (DependencyRow row : rows) {
                Optional<InternalArtifactLinker.ArtifactLink> link = artifactLinker.resolve(
                        orgId, serviceId, row.groupId(), row.artifactId(), linkIndex, localGavs);

                String linkedServiceId = link.map(InternalArtifactLinker.ArtifactLink::serviceId).orElse(null);
                String linkedRepo = link.map(InternalArtifactLinker.ArtifactLink::repo).orElse(null);
                String linkSource = link.map(l -> l.source().name()).orElse(null);
                boolean crossRepo = linkedServiceId != null && !linkedServiceId.equals(serviceId);

                if (sameLink(row, linkedServiceId, linkedRepo, linkSource, crossRepo)) {
                    if (crossRepo) {
                        linkedDeps.add(toFact(orgId, snap, row, linkedServiceId, linkedRepo, linkSource, crossRepo));
                    }
                    continue;
                }

                db.sql("""
                        UPDATE maven_dependency_facts
                        SET linked_service_id = :linkedServiceId,
                            linked_repo = :linkedRepo,
                            link_source = :linkSource,
                            cross_repo = :crossRepo
                        WHERE service_id = :serviceId
                          AND commit_sha = :commitSha
                          AND from_module_path = :fromModulePath
                          AND to_group_id = :groupId
                          AND to_artifact_id = :artifactId
                          AND scope = :scope
                          AND transitive = :transitive
                          AND version_literal = :versionLiteral
                        """)
                        .param("linkedServiceId", linkedServiceId)
                        .param("linkedRepo", linkedRepo)
                        .param("linkSource", linkSource)
                        .param("crossRepo", crossRepo)
                        .param("serviceId", serviceId)
                        .param("commitSha", snap.commitSha())
                        .param("fromModulePath", row.fromModulePath())
                        .param("groupId", row.groupId())
                        .param("artifactId", row.artifactId())
                        .param("scope", row.scope())
                        .param("transitive", row.transitive())
                        .param("versionLiteral", row.versionLiteral() != null ? row.versionLiteral() : "")
                        .update();
                rowsUpdated++;

                if (crossRepo) {
                    linkedDeps.add(toFact(orgId, snap, row, linkedServiceId, linkedRepo, linkSource, crossRepo));
                }
            }

            int ownedBy = 0;
            if (request.syncOwnedByEdges() && !linkedDeps.isEmpty()) {
                ownedBy = mavenGraphProjector.projectOwnedByLinks(orgId, linkedDeps);
            }

            totalUpdated += rowsUpdated;
            totalOwnedBy += ownedBy;
            summaries.add(new MavenLinkBackfillResponse.ServiceBackfillSummary(
                    serviceId, snap.commitSha(), rowsUpdated, ownedBy));
        }

        return new MavenLinkBackfillResponse(orgId, summaries.size(), totalUpdated, totalOwnedBy, summaries);
    }

    private String resolveOrgId(String orgId, String serviceId) {
        if (orgId != null && !orgId.isBlank()) {
            return orgId;
        }
        if (serviceId != null && !serviceId.isBlank()) {
            return registryRepository.findById(serviceId)
                    .map(ServiceEntry::orgId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown serviceId: " + serviceId));
        }
        throw new IllegalArgumentException("orgId or serviceId is required");
    }

    private List<String> resolveServiceIds(String orgId, String serviceId) {
        if (serviceId != null && !serviceId.isBlank()) {
            return List.of(serviceId);
        }
        return db.sql("""
                SELECT DISTINCT mm.service_id
                FROM maven_module_facts mm
                JOIN service_registry sr ON sr.service_id = mm.service_id
                WHERE sr.org_id = :orgId
                ORDER BY mm.service_id
                """)
                .param("orgId", orgId)
                .query(String.class)
                .list();
    }

    private Optional<ServiceSnapshot> loadLatestSnapshot(String serviceId) {
        return db.sql("""
                SELECT org_id, repo, commit_sha
                FROM maven_module_facts
                WHERE service_id = :serviceId
                ORDER BY indexed_at DESC
                LIMIT 1
                """)
                .param("serviceId", serviceId)
                .query((rs, row) -> new ServiceSnapshot(
                        serviceId,
                        rs.getString("org_id"),
                        rs.getString("repo"),
                        rs.getString("commit_sha")))
                .optional();
    }

    private Set<String> loadLocalModuleGavs(String serviceId, String commitSha) {
        List<String> rows = db.sql("""
                SELECT group_id, artifact_id
                FROM maven_module_facts
                WHERE service_id = :serviceId AND commit_sha = :commitSha
                  AND group_id IS NOT NULL AND artifact_id IS NOT NULL
                """)
                .param("serviceId", serviceId)
                .param("commitSha", commitSha)
                .query((rs, n) -> InternalArtifactLinker.gavKey(
                        rs.getString("group_id"), rs.getString("artifact_id")))
                .list();
        return new LinkedHashSet<>(rows);
    }

    private List<DependencyRow> loadDependencyRows(String serviceId, String commitSha) {
        return db.sql("""
                SELECT from_module_path, to_group_id, to_artifact_id, to_version, version_literal,
                       scope, optional, transitive, resolved, unresolved_reason,
                       linked_service_id, linked_repo, link_source, cross_repo,
                       evidence_source, confidence
                FROM maven_dependency_facts
                WHERE service_id = :serviceId AND commit_sha = :commitSha
                """)
                .param("serviceId", serviceId)
                .param("commitSha", commitSha)
                .query((rs, row) -> new DependencyRow(
                        rs.getString("from_module_path"),
                        rs.getString("to_group_id"),
                        rs.getString("to_artifact_id"),
                        rs.getString("to_version"),
                        rs.getString("version_literal"),
                        rs.getString("scope"),
                        rs.getBoolean("optional"),
                        rs.getBoolean("transitive"),
                        rs.getBoolean("resolved"),
                        rs.getString("unresolved_reason"),
                        rs.getString("linked_service_id"),
                        rs.getString("linked_repo"),
                        rs.getString("link_source"),
                        rs.getBoolean("cross_repo"),
                        rs.getString("evidence_source"),
                        rs.getDouble("confidence")))
                .list();
    }

    private static boolean sameLink(
            DependencyRow row,
            String linkedServiceId,
            String linkedRepo,
            String linkSource,
            boolean crossRepo) {
        return nullableEquals(row.linkedServiceId(), linkedServiceId)
                && nullableEquals(row.linkedRepo(), linkedRepo)
                && nullableEquals(row.linkSource(), linkSource)
                && row.crossRepo() == crossRepo;
    }

    private static boolean nullableEquals(String a, String b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }

    private static FactBatch.MavenDependencyFact toFact(
            String orgId,
            ServiceSnapshot snap,
            DependencyRow row,
            String linkedServiceId,
            String linkedRepo,
            String linkSource,
            boolean crossRepo) {
        return new FactBatch.MavenDependencyFact(
                orgId,
                snap.repo(),
                snap.serviceId(),
                snap.commitSha(),
                row.fromModulePath(),
                row.groupId(),
                row.artifactId(),
                row.version(),
                row.versionLiteral(),
                row.scope(),
                row.optional(),
                row.transitive(),
                row.resolved(),
                row.unresolvedReason(),
                linkedServiceId,
                linkedRepo,
                linkSource,
                crossRepo,
                row.evidenceSource(),
                row.confidence());
    }

    private record ServiceSnapshot(String serviceId, String orgId, String repo, String commitSha) {}

    private record DependencyRow(
            String fromModulePath,
            String groupId,
            String artifactId,
            String version,
            String versionLiteral,
            String scope,
            boolean optional,
            boolean transitive,
            boolean resolved,
            String unresolvedReason,
            String linkedServiceId,
            String linkedRepo,
            String linkSource,
            boolean crossRepo,
            String evidenceSource,
            double confidence) {}
}
