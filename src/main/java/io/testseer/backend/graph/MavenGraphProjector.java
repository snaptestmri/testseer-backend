package io.testseer.backend.graph;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.maven.MavenFactOrchestrator;
import io.testseer.backend.registry.ServiceRegistryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

@Service
public class MavenGraphProjector {

    private static final Logger log = LoggerFactory.getLogger(MavenGraphProjector.class);

    private final GraphNodeRepository nodeRepo;
    private final GraphEdgeRepository edgeRepo;
    private final ServiceRegistryRepository registryRepository;
    private final JdbcClient db;

    public MavenGraphProjector(
            GraphNodeRepository nodeRepo,
            GraphEdgeRepository edgeRepo,
            ServiceRegistryRepository registryRepository,
            JdbcClient db) {
        this.nodeRepo = nodeRepo;
        this.edgeRepo = edgeRepo;
        this.registryRepository = registryRepository;
        this.db = db;
    }

    @Transactional
    public void project(FactBatch batch, MavenFactOrchestrator.MavenFacts mavenFacts) {
        if (mavenFacts == null || (mavenFacts.modules().isEmpty() && mavenFacts.dependencies().isEmpty())) {
            return;
        }

        deleteMavenEdgesForService(batch.serviceId());

        for (FactBatch.MavenModuleFact module : mavenFacts.modules()) {
            String nodeId = GraphNodeIds.mavenModuleNode(batch.serviceId(), module.modulePath());
            String label = module.artifactId() != null ? module.artifactId() : module.modulePath();
            nodeRepo.upsert(new GraphNode(
                    nodeId, batch.orgId(), batch.repo(), batch.serviceId(),
                    "service", "MAVEN_MODULE", label));
        }

        for (String edge : mavenFacts.containsModuleEdges()) {
            int sep = edge.indexOf("->");
            if (sep <= 0) {
                continue;
            }
            String parentPath = edge.substring(0, sep);
            String childPath = edge.substring(sep + 2);
            String from = GraphNodeIds.mavenModuleNode(batch.serviceId(), parentPath);
            String to = GraphNodeIds.mavenModuleNode(batch.serviceId(), childPath);
            edgeRepo.insert(new GraphEdge(from, to, "CONTAINS_MODULE", 1.0, "maven-indexer"));
        }

        for (FactBatch.MavenDependencyFact dep : mavenFacts.dependencies()) {
            if (dep.toGroupId() == null || dep.toArtifactId() == null) {
                continue;
            }
            String version = dep.toVersion() != null ? dep.toVersion() : dep.versionLiteral();
            String artifactNodeId = GraphNodeIds.artifactNode(dep.toGroupId(), dep.toArtifactId(), version);
            String label = dep.toGroupId() + ":" + dep.toArtifactId()
                    + (version != null ? ":" + version : "");
            nodeRepo.upsert(new GraphNode(
                    artifactNodeId, batch.orgId(), batch.repo(),
                    dep.linkedServiceId() != null ? dep.linkedServiceId() : batch.serviceId(),
                    "library", "ARTIFACT", label));

            String fromModule = GraphNodeIds.mavenModuleNode(batch.serviceId(), dep.fromModulePath());
            edgeRepo.insert(new GraphEdge(
                    fromModule, artifactNodeId, "DEPENDS_ON_ARTIFACT", dep.confidence(), dep.evidenceSource()));
        }

        projectOwnedByLinks(batch.orgId(), mavenFacts.dependencies());

        log.debug("Maven graph projection for {}: {} modules, {} deps",
                batch.serviceId(), mavenFacts.modules().size(), mavenFacts.dependencies().size());
    }

    @Transactional
    public int projectOwnedByLinks(String orgId, Collection<FactBatch.MavenDependencyFact> dependencies) {
        if (dependencies == null || dependencies.isEmpty()) {
            return 0;
        }
        int count = 0;
        Set<String> seen = new HashSet<>();
        for (FactBatch.MavenDependencyFact dep : dependencies) {
            if (!dep.crossRepo() || dep.linkedServiceId() == null
                    || dep.toGroupId() == null || dep.toArtifactId() == null) {
                continue;
            }
            String version = dep.toVersion() != null ? dep.toVersion() : dep.versionLiteral();
            String artifactNodeId = GraphNodeIds.artifactNode(dep.toGroupId(), dep.toArtifactId(), version);
            String label = dep.toGroupId() + ":" + dep.toArtifactId()
                    + (version != null ? ":" + version : "");
            nodeRepo.upsert(new GraphNode(
                    artifactNodeId, orgId, dep.repo(), dep.linkedServiceId(),
                    "library", "ARTIFACT", label));
            String serviceNodeId = GraphNodeIds.serviceNode(dep.linkedServiceId());
            String key = artifactNodeId + "->" + serviceNodeId;
            if (!seen.add(key)) {
                continue;
            }
            ensureServiceNode(dep.linkedServiceId());
            replaceOwnedByEdge(artifactNodeId, serviceNodeId);
            count++;
        }
        return count;
    }

    private void ensureServiceNode(String serviceId) {
        registryRepository.findById(serviceId).ifPresent(entry ->
                nodeRepo.upsert(GraphNode.service(
                        entry.serviceId(), entry.orgId(), entry.repo(), entry.serviceName())));
    }

    private void replaceOwnedByEdge(String artifactNodeId, String serviceNodeId) {
        db.sql("""
                DELETE FROM graph_edges
                WHERE from_node = :fromNode AND edge_type = 'OWNED_BY'
                """)
                .param("fromNode", artifactNodeId)
                .update();
        edgeRepo.insert(new GraphEdge(artifactNodeId, serviceNodeId, "OWNED_BY", 0.95, "maven-linker"));
    }

    private void deleteMavenEdgesForService(String serviceId) {
        String prefix = serviceId + "::maven::%";
        db.sql("""
                DELETE FROM graph_edges
                WHERE edge_type IN ('CONTAINS_MODULE', 'DEPENDS_ON_ARTIFACT')
                  AND (from_node LIKE :prefix OR to_node LIKE :prefix)
                """)
                .param("prefix", prefix)
                .update();
    }
}
