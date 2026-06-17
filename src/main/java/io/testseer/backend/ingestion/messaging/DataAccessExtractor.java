package io.testseer.backend.ingestion.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.catalog.StoreType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DataAccessExtractor {

    private static final Pattern REPO_CALL =
            Pattern.compile("(\\w+(?:Repo|Dao|Repository))\\.(find\\w+|save|insert|update|delete\\w*)\\s*\\(");
    private static final Pattern TABLE_FROM_ENTITY =
            Pattern.compile("@Table\\s*\\(\\s*name\\s*=\\s*\"([^\"]+)\"");

    private final ObjectMapper mapper = new ObjectMapper();

    public List<FactBatch.DataAccessFact> extract(List<ProtoSchemaExtractor.JavaSourceFile> javaFiles) {
        List<FactBatch.DataAccessFact> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (ProtoSchemaExtractor.JavaSourceFile file : javaFiles) {
            if (file.classFqn() == null) continue;
            Matcher m = REPO_CALL.matcher(file.content());
            while (m.find()) {
                String repo = m.group(1);
                String method = m.group(2);
                String op = operationOf(method);
                String table = inferTable(repo, file.content());
                String key = file.classFqn() + "|" + method + "|" + table + "|" + op;
                if (!seen.add(key)) continue;

                results.add(FactBatch.DataAccessFact.touchpoint(
                        file.classFqn(), inferHandlerMethod(file.content()), op, inferStoreType(repo, file.content()),
                        table, repo, method, correlationKeys(method), null,
                        "JAVA_AST", 0.85
                ));
            }
        }
        return results;
    }

    private String operationOf(String method) {
        String lower = method.toLowerCase(Locale.ROOT);
        if (lower.startsWith("find") || lower.startsWith("get") || lower.startsWith("load")) return "READ";
        if (lower.startsWith("save") || lower.startsWith("insert")) return "WRITE";
        if (lower.startsWith("update") || lower.startsWith("delete")) return "WRITE";
        return "READ";
    }

    private String inferTable(String repo, String content) {
        Matcher tm = TABLE_FROM_ENTITY.matcher(content);
        if (tm.find()) return tm.group(1);

        String base = repo
                .replace("ReadOnlyRepo", "")
                .replace("Repository", "")
                .replace("Repo", "")
                .replace("Dao", "");
        return camelToSnake(base);
    }

    private String camelToSnake(String name) {
        if (name.isEmpty()) return name;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c) && i > 0) sb.append('_');
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private String inferStoreType(String repo, String content) {
        String lowerRepo = repo.toLowerCase(Locale.ROOT);
        if (lowerRepo.contains("mongo")) return StoreType.MONGODB.dbValue();
        if (lowerRepo.contains("cassandra") || lowerRepo.contains("nosql")) return StoreType.CASSANDRA.dbValue();
        if (lowerRepo.contains("bigquery") || lowerRepo.contains("bq")) return StoreType.BIGQUERY.dbValue();
        Matcher fieldType = Pattern.compile(
                "([\\w.]+)\\s+" + Pattern.quote(repo) + "\\s*;").matcher(content);
        if (fieldType.find()) {
            String typeName = fieldType.group(1);
            String lowerType = typeName.toLowerCase(Locale.ROOT);
            if (lowerType.contains("nosql") || lowerType.contains("cassandra")) {
                return StoreType.CASSANDRA.dbValue();
            }
            if (lowerType.contains("mongo")) return StoreType.MONGODB.dbValue();
            if (lowerType.contains("bigquery")) return StoreType.BIGQUERY.dbValue();
            Matcher importLine = Pattern.compile(
                    "import\\s+([\\w.]*" + Pattern.quote(typeName) + ")\\s*;").matcher(content);
            if (importLine.find()) {
                StoreType hinted = StoreType.fromPackageHint(importLine.group(1));
                if (hinted != StoreType.UNKNOWN) {
                    return hinted.dbValue();
                }
            }
        }
        return StoreType.MARIADB.dbValue();
    }

    private String inferHandlerMethod(String content) {
        Matcher m = Pattern.compile("(public|protected)\\s+[\\w<>,\\s]+\\s+(\\w+)\\s*\\([^)]*\\)\\s*\\{").matcher(content);
        while (m.find()) {
            String name = m.group(2);
            if (!name.startsWith("get") && !name.startsWith("set") && !name.equals("equals")) {
                return name;
            }
        }
        return null;
    }

    private String correlationKeys(String daoMethod) {
        List<String> keys = new ArrayList<>();
        if (daoMethod.toLowerCase(Locale.ROOT).contains("offerid")) keys.add("offerId");
        if (daoMethod.toLowerCase(Locale.ROOT).contains("partnerid")) keys.add("partnerId");
        if (keys.isEmpty()) keys.add("offerId");
        try {
            return mapper.writeValueAsString(keys);
        } catch (JsonProcessingException ex) {
            return "[\"offerId\"]";
        }
    }
}
