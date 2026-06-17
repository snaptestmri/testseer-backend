package io.testseer.backend.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GapDetectionService {

    private static final Logger log = LoggerFactory.getLogger(GapDetectionService.class);

    private final JdbcClient db;

    public GapDetectionService(JdbcClient db) {
        this.db = db;
    }

    public GapReport buildReport(String serviceId) {
        String latestSha = db.sql("""
                SELECT commit_sha FROM analysis_runs
                WHERE service_id = :svcId AND status = 'COMPLETE'
                ORDER BY completed_at DESC LIMIT 1
                """)
                .param("svcId", serviceId)
                .query(String.class)
                .optional()
                .orElse(null);

        if (latestSha == null) {
            return new GapReport(serviceId, null, 0, 0, 0, List.of());
        }

        List<GapReport.ClassGap> productionClasses = db.sql("""
                SELECT DISTINCT ON (symbol_fqn) symbol_fqn, file_path, attributes::text
                FROM symbol_facts
                WHERE service_id = :svcId
                  AND symbol_kind = 'CLASS'
                  AND file_path LIKE 'src/main/java/%'
                  AND commit_sha = :sha
                ORDER BY symbol_fqn
                """)
                .param("svcId", serviceId)
                .param("sha", latestSha)
                .query((rs, row) -> new GapReport.ClassGap(
                        rs.getString("symbol_fqn"),
                        rs.getString("file_path"),
                        isController(rs.getString("attributes")) ? "ENDPOINT_CONTROLLER" : "CLASS"
                ))
                .list();

        Set<String> testFqns = db.sql("""
                SELECT DISTINCT symbol_fqn
                FROM symbol_facts
                WHERE service_id = :svcId
                  AND symbol_kind = 'CLASS'
                  AND (file_path LIKE 'src/test/java/%'
                       OR symbol_fqn LIKE '%Test'
                       OR symbol_fqn LIKE '%Tests'
                       OR symbol_fqn LIKE '%IT')
                  AND commit_sha = :sha
                """)
                .param("svcId", serviceId)
                .param("sha", latestSha)
                .query(String.class)
                .list()
                .stream()
                .collect(Collectors.toSet());

        List<GapReport.ClassGap> gaps = productionClasses.stream()
                .filter(c -> !hasTest(c.classFqn(), testFqns))
                .toList();

        int tested = productionClasses.size() - gaps.size();
        log.info("Gap report for {}: {}/{} classes have tests",
                serviceId, tested, productionClasses.size());

        return new GapReport(serviceId, latestSha,
                productionClasses.size(), tested, gaps.size(), gaps);
    }

    static boolean hasTest(String classFqn, Set<String> testFqns) {
        String simpleName = TestClassMatcher.simpleName(classFqn);
        return testFqns.stream().anyMatch(t -> TestClassMatcher.matches(simpleName, t));
    }

    private static boolean isController(String attributesJson) {
        if (attributesJson == null) return false;
        return attributesJson.contains("Controller") || attributesJson.contains("RestController");
    }
}
