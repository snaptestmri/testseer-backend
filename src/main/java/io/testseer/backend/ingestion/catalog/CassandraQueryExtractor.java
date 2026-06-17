package io.testseer.backend.ingestion.catalog;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.messaging.ProtoSchemaExtractor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CassandraQueryExtractor {

    private static final Pattern REPO_INTERFACE =
            Pattern.compile("(?:public\\s+)?interface\\s+(\\w+)([^{]*)\\{");
    private static final Pattern ENTITY_GENERIC =
            Pattern.compile("(?:CassandraRepository|BaseNoSqlRepository)\\s*<\\s*(\\w+)");
    private static final Pattern QUERY_METHOD = Pattern.compile(
            "@Query\\(\"((?:[^\"\\\\]|\\\\.)*)\"\\)(?:[\\s\\S]*?)\\s*(?:public\\s+)?(?:[\\w<>,\\[\\].\\s]+?)\\s+(\\w+)\\s*\\(",
            Pattern.MULTILINE);
    private static final Pattern CQL_TABLE = Pattern.compile("[\"']([A-Za-z][A-Za-z0-9_]*)[\"']");

    private final StoreTypeInferencer storeTypeInferencer;

    public CassandraQueryExtractor(StoreTypeInferencer storeTypeInferencer) {
        this.storeTypeInferencer = storeTypeInferencer;
    }

    public List<FactBatch.AccessorMethodFact> extractAndMerge(
            List<ProtoSchemaExtractor.JavaSourceFile> javaFiles,
            List<FactBatch.DataObjectFact> entities,
            List<FactBatch.AccessorMethodFact> existing) {

        Map<String, FactBatch.AccessorMethodFact> byKey = new LinkedHashMap<>();
        for (FactBatch.AccessorMethodFact f : existing) {
            byKey.put(key(f.accessorFqn(), f.methodName()), f);
        }

        Map<String, FactBatch.DataObjectFact> entityByFqn = new LinkedHashMap<>();
        Map<String, FactBatch.DataObjectFact> entityByPhysical = new LinkedHashMap<>();
        for (FactBatch.DataObjectFact e : entities) {
            entityByFqn.put(e.entityFqn(), e);
            entityByPhysical.put(e.physicalName(), e);
        }

        for (ProtoSchemaExtractor.JavaSourceFile file : javaFiles) {
            if (file.classFqn() == null || !isCassandraRepo(file)) continue;
            ImportIndex imports = ImportIndex.build(file.content());
            Matcher im = REPO_INTERFACE.matcher(file.content());
            if (!im.find()) continue;
            String entitySimple = null;
            Matcher gm = ENTITY_GENERIC.matcher(im.group(2));
            if (gm.find()) entitySimple = gm.group(1);
            String entityFqn = entitySimple != null
                    ? resolveEntityFqn(file.content(), file.classFqn(), entitySimple)
                    : null;

            Matcher qm = QUERY_METHOD.matcher(file.content());
            while (qm.find()) {
                String cql = qm.group(1);
                String methodName = qm.group(2);
                String table = extractCqlTable(cql);
                String operation = operationFromCql(cql);
                FactBatch.DataObjectFact entity = entityFqn != null ? entityByFqn.get(entityFqn) : null;
                if (entity == null && table != null) entity = entityByPhysical.get(table);
                String physical = table != null ? table : (entity != null ? entity.physicalName() : null);
                if (entityFqn == null && entity != null) entityFqn = entity.entityFqn();

                String k = key(file.classFqn(), methodName);
                if (byKey.containsKey(k)) continue;

                byKey.put(k, new FactBatch.AccessorMethodFact(
                        "REPO",
                        file.classFqn(),
                        methodName,
                        operation,
                        entityFqn,
                        entity != null ? entity.domainFqn() : null,
                        StoreType.CASSANDRA.dbValue(),
                        physical,
                        "CASSANDRA_QUERY",
                        0.90
                ));
            }

            extractInheritedRepoMethods(file, entityFqn, entityByFqn, byKey);
        }
        return List.copyOf(byKey.values());
    }

    private void extractInheritedRepoMethods(
            ProtoSchemaExtractor.JavaSourceFile file,
            String entityFqn,
            Map<String, FactBatch.DataObjectFact> entityByFqn,
            Map<String, FactBatch.AccessorMethodFact> byKey) {

        FactBatch.DataObjectFact entity = entityFqn != null ? entityByFqn.get(entityFqn) : null;
        Pattern repoMethod = Pattern.compile("\\s*(?:public\\s+)?(?:[\\w<>,\\[\\].\\s]+?)\\s+(save|delete\\w*|insert\\w*)\\s*\\(", Pattern.MULTILINE);
        Matcher m = repoMethod.matcher(file.content());
        while (m.find()) {
            String methodName = m.group(1);
            String k = key(file.classFqn(), methodName);
            if (byKey.containsKey(k)) continue;
            byKey.put(k, new FactBatch.AccessorMethodFact(
                    "REPO",
                    file.classFqn(),
                    methodName,
                    DaoMethodExtractor.operationOf(methodName),
                    entityFqn,
                    entity != null ? entity.domainFqn() : null,
                    StoreType.CASSANDRA.dbValue(),
                    entity != null ? entity.physicalName() : null,
                    "CASSANDRA_REPO",
                    0.88
            ));
        }
    }

    private static boolean isCassandraRepo(ProtoSchemaExtractor.JavaSourceFile file) {
        String fqn = file.classFqn();
        return fqn.contains(".nosql.") || fqn.contains("Cassandra");
    }

    private static String key(String accessorFqn, String methodName) {
        return accessorFqn + "|" + methodName;
    }

    private static String resolveEntityFqn(String content, String repoFqn, String entitySimple) {
        Matcher importMatcher = Pattern.compile(
                "import\\s+([\\w.]+\\." + Pattern.quote(entitySimple) + ")\\s*;").matcher(content);
        if (importMatcher.find()) return importMatcher.group(1);
        int dot = repoFqn.lastIndexOf('.');
        if (dot < 0) return entitySimple;
        return repoFqn.substring(0, dot + 1).replace(".repo.", ".entities.") + entitySimple;
    }

    static String extractCqlTable(String cql) {
        Matcher m = CQL_TABLE.matcher(cql);
        if (m.find()) return m.group(1);
        return null;
    }

    static String operationFromCql(String cql) {
        String upper = cql.trim().toUpperCase(Locale.ROOT);
        if (upper.startsWith("UPDATE") || upper.startsWith("INSERT") || upper.startsWith("DELETE")) {
            return "WRITE";
        }
        return "READ";
    }
}
