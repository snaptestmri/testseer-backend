package io.testseer.backend.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.graph.GraphNodeIds;
import io.testseer.backend.graph.GraphProjectionService;
import io.testseer.backend.graph.ReachabilityResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ImpactAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ImpactAnalysisService.class);

    private final JdbcClient db;
    private final ObjectMapper mapper;
    private final GraphProjectionService graphService;

    public ImpactAnalysisService(JdbcClient db,
                                 ObjectMapper mapper,
                                 GraphProjectionService graphService) {
        this.db           = db;
        this.mapper       = mapper;
        this.graphService = graphService;
    }

    public ImpactReport buildReport(String serviceId, String commitSha) {
        List<ImpactReport.ChangedSymbol> changed = loadChangedSymbols(serviceId, commitSha);
        if (changed == null) changed = List.of();
        List<ImpactReport.AffectedConsumer> consumers = findAffectedConsumers(serviceId, changed);
        List<ImpactReport.DownstreamDependency> downstream = loadDownstreamDependencies(
                serviceId, commitSha, changed);
        List<ImpactReport.ExternalDependency> externalDownstream =
                loadExternalDownstreamDependencies(serviceId, commitSha, changed);
        List<ImpactReport.SuggestedTest> suggestions = buildSuggestions(
                serviceId, changed, consumers, externalDownstream);
        List<String> missing = computeMissingTestClasses(serviceId, changed);
        List<ImpactReport.ArtifactImpact> artifactImpact = loadArtifactImpact(serviceId, commitSha);

        return new ImpactReport(serviceId, commitSha, changed, consumers,
                downstream, externalDownstream, suggestions, missing, artifactImpact);
    }

    private List<String> computeMissingTestClasses(String serviceId,
                                                    List<ImpactReport.ChangedSymbol> changed) {
        Set<String> testFqns = loadTestClassFqns(serviceId);
        List<String> missing = new ArrayList<>();
        for (ImpactReport.ChangedSymbol sym : changed) {
            if (!"CLASS".equals(sym.symbolKind())) continue;
            if (isTestPath(sym.filePath())) continue;
            String simple = TestClassMatcher.simpleName(sym.symbolFqn());
            boolean hasTest = testFqns.stream()
                    .anyMatch(t -> TestClassMatcher.matches(simple, t));
            if (!hasTest) missing.add(sym.symbolFqn());
        }
        return missing;
    }

    private List<ImpactReport.ChangedSymbol> loadChangedSymbols(String serviceId, String commitSha) {
        return db.sql("""
                SELECT symbol_fqn, symbol_kind, file_path, attributes::text
                FROM symbol_facts
                WHERE service_id = :svcId AND commit_sha = :sha
                ORDER BY symbol_kind, symbol_fqn
                """)
                .param("svcId", serviceId)
                .param("sha", commitSha)
                .query((rs, row) -> {
                    String attrs = rs.getString("attributes");
                    return new ImpactReport.ChangedSymbol(
                            rs.getString("symbol_fqn"),
                            rs.getString("symbol_kind"),
                            rs.getString("file_path"),
                            extractAttr(attrs, "httpMethod"),
                            extractAttr(attrs, "path")
                    );
                })
                .list();
    }

    private List<ImpactReport.AffectedConsumer> findAffectedConsumers(
            String serviceId, List<ImpactReport.ChangedSymbol> changed) {

        Map<String, ImpactReport.AffectedConsumer> deduped = new LinkedHashMap<>();

        for (ImpactReport.ChangedSymbol sym : changed) {
            findGraphNodeId(serviceId, sym).ifPresent(nodeId -> {
                ReachabilityResult result = graphService.reverseReachability(nodeId);
                for (String affectedId : result.nodeIds()) {
                    hydrateConsumer(affectedId, "GRAPH").ifPresent(c ->
                            deduped.put(consumerKey(c), c));
                }
            });

            if ("ENDPOINT".equals(sym.symbolKind())
                    && sym.httpMethod() != null && sym.path() != null) {
                loadOutboundCallers(serviceId, sym.httpMethod(), sym.path())
                        .forEach(c -> deduped.put(consumerKey(c), c));
            }
        }
        return new ArrayList<>(deduped.values());
    }

    private java.util.Optional<String> findGraphNodeId(String serviceId,
                                                        ImpactReport.ChangedSymbol sym) {
        String nodeType = "ENDPOINT".equals(sym.symbolKind()) ? "ENDPOINT" : "CLASS";
        return db.sql("""
                SELECT id FROM graph_nodes
                WHERE symbol_fqn = :fqn AND node_type = :nodeType
                LIMIT 1
                """)
                .param("fqn", sym.symbolFqn())
                .param("nodeType", nodeType)
                .query(String.class)
                .optional()
                .or(() -> {
                    if ("CLASS".equals(sym.symbolKind())) {
                        return java.util.Optional.of(
                                GraphNodeIds.classNode(serviceId, sym.symbolFqn()));
                    }
                    return java.util.Optional.empty();
                });
    }

    private java.util.Optional<ImpactReport.AffectedConsumer> hydrateConsumer(
            String nodeId, String source) {
        return db.sql("""
                SELECT gn.id, gn.node_type, gn.symbol_fqn, gn.service,
                       sr.service_id, sr.service_name
                FROM graph_nodes gn
                LEFT JOIN service_registry sr ON sr.service_name = gn.service
                WHERE gn.id = :id
                LIMIT 1
                """)
                .param("id", nodeId)
                .query((rs, row) -> new ImpactReport.AffectedConsumer(
                        source,
                        rs.getString("service_id"),
                        rs.getString("service_name"),
                        classFromNode(rs.getString("node_type"), rs.getString("symbol_fqn")),
                        rs.getString("node_type"),
                        null,
                        null
                ))
                .optional();
    }

    private List<ImpactReport.AffectedConsumer> loadOutboundCallers(
            String serviceId, String httpMethod, String path) {
        return db.sql("""
                SELECT ocf.source_symbol, ocf.http_method, ocf.path,
                       sr.service_id, sr.service_name
                FROM outbound_call_facts ocf
                JOIN service_registry sr ON sr.service_id = ocf.service_id
                WHERE ocf.service_id != :svcId
                  AND ocf.http_method = :method
                  AND ocf.path = :path
                """)
                .param("svcId", serviceId)
                .param("method", httpMethod)
                .param("path", path)
                .query((rs, row) -> new ImpactReport.AffectedConsumer(
                        "OUTBOUND_CALL",
                        rs.getString("service_id"),
                        rs.getString("service_name"),
                        rs.getString("source_symbol"),
                        "CLASS",
                        rs.getString("http_method"),
                        rs.getString("path")
                ))
                .list();
    }

    private List<ImpactReport.DownstreamDependency> loadDownstreamDependencies(
            String serviceId, String commitSha, List<ImpactReport.ChangedSymbol> changed) {

        if (changed == null || changed.isEmpty()) return List.of();

        Set<String> classFqns = new HashSet<>();
        for (ImpactReport.ChangedSymbol sym : changed) {
            if ("CLASS".equals(sym.symbolKind())) {
                classFqns.add(sym.symbolFqn());
            }
        }

        if (classFqns.isEmpty()) return List.of();

        return db.sql("""
                SELECT source_symbol, http_method, path
                FROM outbound_call_facts
                WHERE service_id = :svcId
                  AND commit_sha = :sha
                  AND http_method IS NOT NULL
                  AND source_symbol = ANY(:fqns)
                ORDER BY source_symbol, path
                """)
                .param("svcId", serviceId)
                .param("sha", commitSha)
                .param("fqns", classFqns.toArray(new String[0]))
                .query((rs, row) -> new ImpactReport.DownstreamDependency(
                        rs.getString("source_symbol"),
                        rs.getString("http_method"),
                        rs.getString("path")
                ))
                .list();
    }

    private List<ImpactReport.ExternalDependency> loadExternalDownstreamDependencies(
            String serviceId, String commitSha, List<ImpactReport.ChangedSymbol> changed) {

        if (changed == null || changed.isEmpty()) return List.of();

        Set<String> classFqns = new HashSet<>();
        for (ImpactReport.ChangedSymbol sym : changed) {
            if ("CLASS".equals(sym.symbolKind())) {
                classFqns.add(sym.symbolFqn());
            }
        }
        if (classFqns.isEmpty()) return List.of();

        return db.sql("""
                SELECT DISTINCT e.caller_class_fqn, e.endpoint_id, e.partner_slug, e.operation,
                       e.http_method, e.url_resolved, e.config_key, e.flow_step, e.boundary
                FROM external_endpoint_facts e
                WHERE e.service_id = :svcId
                  AND e.commit_sha = :sha
                  AND e.caller_class_fqn = ANY(:fqns)
                ORDER BY e.endpoint_id
                """)
                .param("svcId", serviceId)
                .param("sha", commitSha)
                .param("fqns", classFqns.toArray(new String[0]))
                .query((rs, row) -> new ImpactReport.ExternalDependency(
                        rs.getString("caller_class_fqn"),
                        rs.getString("endpoint_id"),
                        rs.getString("partner_slug"),
                        rs.getString("operation"),
                        rs.getString("http_method"),
                        rs.getString("url_resolved"),
                        rs.getString("config_key"),
                        rs.getString("flow_step"),
                        rs.getString("boundary")
                ))
                .list();
    }

    private List<ImpactReport.SuggestedTest> buildSuggestions(
            String serviceId,
            List<ImpactReport.ChangedSymbol> changed,
            List<ImpactReport.AffectedConsumer> consumers,
            List<ImpactReport.ExternalDependency> externalDownstream) {

        List<ImpactReport.SuggestedTest> suggestions = new ArrayList<>();
        Set<String> testFqns = loadTestClassFqns(serviceId);
        Set<String> seen = new HashSet<>();

        for (ImpactReport.ChangedSymbol sym : changed) {
            if (!"CLASS".equals(sym.symbolKind()) || isTestPath(sym.filePath())) continue;
            String simple = TestClassMatcher.simpleName(sym.symbolFqn());
            List<String> matches = testFqns.stream()
                    .filter(t -> TestClassMatcher.matches(simple, t))
                    .toList();

            if (matches.isEmpty()) {
                String key = "UNIT:missing:" + sym.symbolFqn();
                if (seen.add(key)) {
                    suggestions.add(new ImpactReport.SuggestedTest(
                            "UNIT", null, null, false,
                            "No test class found for " + simple));
                }
            } else {
                for (String testFqn : matches) {
                    String key = "UNIT:" + testFqn;
                    if (seen.add(key)) {
                        suggestions.add(new ImpactReport.SuggestedTest(
                                "UNIT", testFqn, null, true,
                                "Tests changed class " + simple));
                    }
                }
            }
        }

        for (ImpactReport.AffectedConsumer consumer : consumers) {
            if (consumer.consumerServiceId() == null) continue;
            Set<String> callerTests = loadTestClassFqns(consumer.consumerServiceId());
            String endpointDesc = consumer.httpMethod() != null && consumer.path() != null
                    ? consumer.httpMethod() + " " + consumer.path()
                    : "changed dependency";

            List<String> integrationMatches = callerTests.stream()
                    .filter(t -> t.contains("Integration") || t.endsWith("IT"))
                    .toList();

            if (integrationMatches.isEmpty()) {
                String key = "INT:missing:" + consumer.consumerServiceId();
                if (seen.add(key)) {
                    suggestions.add(new ImpactReport.SuggestedTest(
                            "INTEGRATION", null, consumer.consumerServiceName(), false,
                            "Service '" + consumer.consumerServiceName()
                                    + "' may need integration tests — calls " + endpointDesc));
                }
            } else {
                for (String testFqn : integrationMatches) {
                    String key = "INT:" + testFqn;
                    if (seen.add(key)) {
                        suggestions.add(new ImpactReport.SuggestedTest(
                                "INTEGRATION", testFqn, consumer.consumerServiceName(), true,
                                "Calls changed endpoint " + endpointDesc));
                    }
                }
            }
        }

        for (ImpactReport.ExternalDependency ext : externalDownstream) {
            String key = "EXT:" + ext.endpointId() + ":" + ext.callerClass();
            if (seen.add(key)) {
                suggestions.add(new ImpactReport.SuggestedTest(
                        "INTEGRATION",
                        null,
                        ext.partnerSlug(),
                        false,
                        "Re-test external " + ext.httpMethod() + " " + ext.operation()
                                + " (" + ext.endpointId() + ") — config "
                                + ext.configKey()));
            }
        }
        return suggestions;
    }

    private Set<String> loadTestClassFqns(String serviceId) {
        List<String> fqns = db.sql("""
                SELECT DISTINCT symbol_fqn
                FROM symbol_facts
                WHERE service_id = :svcId
                  AND symbol_kind = 'CLASS'
                  AND (file_path LIKE 'src/test/java/%'
                       OR symbol_fqn LIKE '%Test'
                       OR symbol_fqn LIKE '%IT')
                  AND commit_sha = (
                      SELECT commit_sha FROM analysis_runs
                      WHERE service_id = :svcId AND status = 'COMPLETE'
                      ORDER BY completed_at DESC LIMIT 1
                  )
                """)
                .param("svcId", serviceId)
                .query(String.class)
                .list();
        return fqns == null ? Set.of() : new HashSet<>(fqns);
    }

    private static boolean isTestPath(String filePath) {
        return filePath != null && filePath.contains("src/test/java");
    }

    private static String classFromNode(String nodeType, String symbolFqn) {
        if ("SERVICE".equals(nodeType)) return null;
        if (symbolFqn != null && symbolFqn.contains("#")) {
            return symbolFqn.substring(0, symbolFqn.indexOf('#'));
        }
        return symbolFqn;
    }

    private static String consumerKey(ImpactReport.AffectedConsumer c) {
        return c.source() + "|" + c.consumerServiceId() + "|" + c.consumerClass()
                + "|" + c.httpMethod() + "|" + c.path();
    }

    private List<ImpactReport.ArtifactImpact> loadArtifactImpact(String serviceId, String commitSha) {
        List<DepRow> current = db.sql("""
                SELECT to_group_id, to_artifact_id, to_version, version_literal
                FROM maven_dependency_facts
                WHERE service_id = :svcId AND commit_sha = :sha AND resolved = true
                """)
                .param("svcId", serviceId)
                .param("sha", commitSha)
                .query((rs, row) -> new DepRow(
                        rs.getString("to_group_id"),
                        rs.getString("to_artifact_id"),
                        rs.getString("to_version"),
                        rs.getString("version_literal")))
                .list();
        if (current.isEmpty()) {
            return List.of();
        }

        String orgId = db.sql("SELECT org_id FROM service_registry WHERE service_id = :svcId")
                .param("svcId", serviceId)
                .query(String.class)
                .optional()
                .orElse(null);
        if (orgId == null) {
            return List.of();
        }

        List<ImpactReport.ArtifactImpact> impacts = new ArrayList<>();
        Map<String, String> previousVersions = loadPreviousDependencyVersions(serviceId, commitSha);

        for (DepRow dep : current) {
            String coord = dep.groupId() + ":" + dep.artifactId();
            String newVersion = dep.version() != null ? dep.version() : dep.versionLiteral();
            String previousVersion = previousVersions.get(coord);
            if (previousVersion != null && previousVersion.equals(newVersion)) {
                continue;
            }

            List<ImpactReport.DownstreamArtifactService> downstream = db.sql("""
                    SELECT DISTINCT ON (m.service_id) m.service_id, r.repo, m.to_version
                    FROM maven_dependency_facts m
                    JOIN service_registry r ON r.service_id = m.service_id
                    WHERE r.org_id = :orgId
                      AND m.to_group_id = :groupId
                      AND m.to_artifact_id = :artifactId
                      AND m.service_id <> :svcId
                      AND m.resolved = true
                    ORDER BY m.service_id, m.indexed_at DESC
                    """)
                    .param("orgId", orgId)
                    .param("groupId", dep.groupId())
                    .param("artifactId", dep.artifactId())
                    .param("svcId", serviceId)
                    .query((rs, row) -> new ImpactReport.DownstreamArtifactService(
                            rs.getString("service_id"),
                            rs.getString("repo"),
                            rs.getString("to_version")))
                    .list()
                    .stream()
                    .filter(d -> d.pinnedVersion() != null && !d.pinnedVersion().equals(newVersion))
                    .toList();

            if (previousVersion != null || !downstream.isEmpty()) {
                impacts.add(new ImpactReport.ArtifactImpact(
                        coord, previousVersion, newVersion, downstream));
            }
        }
        return impacts;
    }

    private Map<String, String> loadPreviousDependencyVersions(String serviceId, String commitSha) {
        List<DepRow> previous = db.sql("""
                SELECT to_group_id, to_artifact_id, to_version, version_literal
                FROM maven_dependency_facts
                WHERE service_id = :svcId
                  AND commit_sha <> :sha
                  AND commit_sha IN (
                      SELECT commit_sha FROM analysis_runs
                      WHERE service_id = :svcId AND status = 'COMPLETE' AND commit_sha <> :sha
                      ORDER BY completed_at DESC LIMIT 1
                  )
                """)
                .param("svcId", serviceId)
                .param("sha", commitSha)
                .query((rs, row) -> new DepRow(
                        rs.getString("to_group_id"),
                        rs.getString("to_artifact_id"),
                        rs.getString("to_version"),
                        rs.getString("version_literal")))
                .list();

        Map<String, String> map = new LinkedHashMap<>();
        for (DepRow row : previous) {
            String version = row.version() != null ? row.version() : row.versionLiteral();
            map.put(row.groupId() + ":" + row.artifactId(), version);
        }
        return map;
    }

    private record DepRow(String groupId, String artifactId, String version, String versionLiteral) {}

    private String extractAttr(String jsonAttrs, String key) {
        if (jsonAttrs == null) return null;
        try {
            JsonNode node = mapper.readTree(jsonAttrs);
            JsonNode val = node.get(key);
            return val != null && !val.isNull() ? val.asText() : null;
        } catch (Exception ex) {
            log.debug("Could not parse attributes JSON: {}", ex.getMessage());
            return null;
        }
    }
}
