package io.testseer.backend.ingestion.catalog;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.GitHubSourceFetcher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Phase 5: extract MariaDB / Cassandra DDL tables into schema_object_facts. */
@Component
public class SchemaDdlExtractor {

    private static final Pattern MARIADB_CREATE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                    + "(?:[`\"]?([\\w]+)[`\"]?\\.)?(?:[`\"]?([\\w]+)[`\"]?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CQL_CREATE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:\"([\\w]+)\"|([\\w]+))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern USE_KEYSPACE = Pattern.compile(
            "USE\\s+[\"']?([\\w]+)[\"']?\\s*;", Pattern.CASE_INSENSITIVE);

    public List<FactBatch.SchemaObjectFact> extract(List<GitHubSourceFetcher.FetchedFile> ddlFiles) {
        List<FactBatch.SchemaObjectFact> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (GitHubSourceFetcher.FetchedFile file : ddlFiles) {
            if (file.path() == null || file.content() == null) continue;
            String lower = file.path().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".sql")) {
                results.addAll(extractMariaDb(file.path(), file.content(), seen));
            } else if (lower.endsWith(".cql")) {
                results.addAll(extractCassandra(file.path(), file.content(), seen));
            }
        }
        return results;
    }

    private List<FactBatch.SchemaObjectFact> extractMariaDb(String path, String content, Set<String> seen) {
        String pathCatalog = inferMariaCatalog(path);
        List<FactBatch.SchemaObjectFact> facts = new ArrayList<>();
        Matcher m = MARIADB_CREATE.matcher(content);
        while (m.find()) {
            String catalog = m.group(1) != null ? m.group(1) : pathCatalog;
            String table = m.group(2);
            if (table == null || table.isBlank()) continue;
            String key = "MARIADB|" + table + "|" + nullToEmpty(catalog);
            if (!seen.add(key)) continue;
            facts.add(new FactBatch.SchemaObjectFact(
                    StoreType.MARIADB.dbValue(), table, catalog, path, "DDL_FILE"));
        }
        return facts;
    }

    private List<FactBatch.SchemaObjectFact> extractCassandra(String path, String content, Set<String> seen) {
        String keyspace = inferCassandraKeyspace(path);
        Matcher use = USE_KEYSPACE.matcher(content);
        if (use.find()) {
            keyspace = use.group(1);
        }
        List<FactBatch.SchemaObjectFact> facts = new ArrayList<>();
        Matcher m = CQL_CREATE.matcher(content);
        while (m.find()) {
            String table = m.group(1) != null ? m.group(1) : m.group(2);
            if (table == null || table.isBlank()) continue;
            String key = "CASSANDRA|" + table + "|" + nullToEmpty(keyspace);
            if (!seen.add(key)) continue;
            facts.add(new FactBatch.SchemaObjectFact(
                    StoreType.CASSANDRA.dbValue(), table, keyspace, path, "DDL_FILE"));
        }
        return facts;
    }

    static String inferMariaCatalog(String path) {
        Matcher m = Pattern.compile("MariaDB[/\\\\]([^/\\\\]+)[/\\\\]", Pattern.CASE_INSENSITIVE)
                .matcher(path.replace('\\', '/'));
        return m.find() ? m.group(1) : null;
    }

    static String inferCassandraKeyspace(String path) {
        Matcher m = Pattern.compile("Cassandra[/\\\\]([^/\\\\]+)[/\\\\]", Pattern.CASE_INSENSITIVE)
                .matcher(path.replace('\\', '/'));
        return m.find() ? m.group(1) : null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
