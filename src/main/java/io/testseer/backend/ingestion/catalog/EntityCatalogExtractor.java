package io.testseer.backend.ingestion.catalog;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.KotlinSourceLightParser;
import io.testseer.backend.ingestion.messaging.ProtoSchemaExtractor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class EntityCatalogExtractor {

    private static final Pattern JPA_TABLE_FALLBACK =
            Pattern.compile("@Table\\s*\\(\\s*name\\s*=\\s*[\"']([^\"']+)[\"'](?:[^)]*catalog\\s*=\\s*[\"']([^\"']+)[\"'])?");
    private static final Pattern DOCUMENT_COLLECTION_FALLBACK =
            Pattern.compile("@Document\\s*\\(\\s*(?:collection\\s*=\\s*)?[\"']([^\"']+)[\"']");
    private static final Pattern CASSANDRA_TABLE_FALLBACK =
            Pattern.compile("@Table\\s*\\(\\s*(?:value\\s*=\\s*)?[\"']([^\"']+)[\"']");

    private final StoreTypeInferencer storeTypeInferencer;
    private final EntityAnnotationParser annotationParser;

    public EntityCatalogExtractor(
            StoreTypeInferencer storeTypeInferencer,
            EntityAnnotationParser annotationParser) {
        this.storeTypeInferencer = storeTypeInferencer;
        this.annotationParser = annotationParser;
    }

    public List<FactBatch.DataObjectFact> extract(List<ProtoSchemaExtractor.JavaSourceFile> javaFiles) {
        List<FactBatch.DataObjectFact> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (ProtoSchemaExtractor.JavaSourceFile file : javaFiles) {
            if (file.path() != null && file.path().endsWith(".kt")) {
                results.addAll(extractKotlin(file, seen));
                continue;
            }
            if (file.classFqn() == null) continue;
            String content = file.content();
            List<String> annotations = annotationNames(content);
            StoreType storeType = storeTypeInferencer.inferFromEntity(file.classFqn(), annotations, content);
            if (storeType == StoreType.UNKNOWN) continue;

            String physicalName = resolvePhysicalName(storeType, content, file.classFqn());
            if (physicalName == null || physicalName.isBlank()) continue;

            String catalog = resolveCatalog(storeType, content);
            String kind = tableKind(storeType);
            String domainFqn = inferDomainFqn(file.classFqn());
            String key = file.classFqn() + "|" + storeType + "|" + physicalName;
            if (!seen.add(key)) continue;

            results.add(new FactBatch.DataObjectFact(
                    file.classFqn(),
                    domainFqn,
                    storeType.dbValue(),
                    physicalName,
                    catalog,
                    kind,
                    "ENTITY_ANNOTATION",
                    confidenceFor(storeType, domainFqn, physicalName, content),
                    null
            ));
        }
        return results;
    }

    private List<FactBatch.DataObjectFact> extractKotlin(
            ProtoSchemaExtractor.JavaSourceFile file, Set<String> seen) {
        List<FactBatch.DataObjectFact> results = new ArrayList<>();
        String content = file.content();

        for (KotlinSourceLightParser.KotlinDocumentType doc :
                KotlinSourceLightParser.documentTypes(file.path(), content)) {
            String key = doc.classFqn() + "|MONGODB|" + doc.collection();
            if (!seen.add(key)) continue;
            results.add(new FactBatch.DataObjectFact(
                    doc.classFqn(),
                    null,
                    StoreType.MONGODB.dbValue(),
                    doc.collection(),
                    null,
                    "COLLECTION",
                    "ENTITY_ANNOTATION",
                    0.95,
                    null
            ));
        }

        if (file.classFqn() != null && content.contains("@Entity")) {
            List<String> annotations = annotationNames(content);
            StoreType storeType = storeTypeInferencer.inferFromEntity(file.classFqn(), annotations, content);
            if (storeType != StoreType.UNKNOWN) {
                String physicalName = resolvePhysicalName(storeType, content, file.classFqn());
                if (physicalName != null && !physicalName.isBlank()) {
                    String key = file.classFqn() + "|" + storeType + "|" + physicalName;
                    if (seen.add(key)) {
                        results.add(new FactBatch.DataObjectFact(
                                file.classFqn(),
                                inferDomainFqn(file.classFqn()),
                                storeType.dbValue(),
                                physicalName,
                                resolveCatalog(storeType, content),
                                tableKind(storeType),
                                "ENTITY_ANNOTATION",
                                confidenceFor(storeType, inferDomainFqn(file.classFqn()), physicalName, content),
                                null
                        ));
                    }
                }
            }
        }
        return results;
    }

    private static List<String> annotationNames(String content) {
        List<String> names = new ArrayList<>();
        Matcher m = Pattern.compile("@(\\w+)").matcher(content);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    private String resolvePhysicalName(StoreType storeType, String content, String classFqn) {
        if (storeType == StoreType.MONGODB) {
            return annotationParser.mongoDocument(content)
                    .map(EntityAnnotationParser.DocumentInfo::collection)
                    .or(() -> regexDocument(content))
                    .orElseGet(() -> camelToSnake(simpleName(classFqn)));
        }
        if (storeType == StoreType.CASSANDRA) {
            return annotationParser.cassandraTable(content)
                    .map(EntityAnnotationParser.CassandraTableInfo::value)
                    .or(() -> regexCassandra(content))
                    .orElseGet(() -> simpleName(classFqn));
        }
        return annotationParser.jpaTable(content)
                .map(EntityAnnotationParser.JpaTableInfo::name)
                .or(() -> regexJpaTable(content))
                .orElseGet(() -> simpleName(classFqn).replace("Entity", ""));
    }

    private String resolveCatalog(StoreType storeType, String content) {
        if (storeType != StoreType.MARIADB) return null;
        return annotationParser.jpaTable(content)
                .map(EntityAnnotationParser.JpaTableInfo::catalog)
                .or(() -> regexJpaTableCatalog(content))
                .orElse(null);
    }

    private static java.util.Optional<String> regexJpaTable(String content) {
        Matcher m = JPA_TABLE_FALLBACK.matcher(content);
        return m.find() ? java.util.Optional.of(m.group(1)) : java.util.Optional.empty();
    }

    private static java.util.Optional<String> regexJpaTableCatalog(String content) {
        Matcher m = JPA_TABLE_FALLBACK.matcher(content);
        if (m.find() && m.group(2) != null && !m.group(2).isBlank()) {
            return java.util.Optional.of(m.group(2));
        }
        return java.util.Optional.empty();
    }

    private static java.util.Optional<String> regexDocument(String content) {
        Matcher m = DOCUMENT_COLLECTION_FALLBACK.matcher(content);
        return m.find() ? java.util.Optional.of(m.group(1)) : java.util.Optional.empty();
    }

    private static java.util.Optional<String> regexCassandra(String content) {
        Matcher m = CASSANDRA_TABLE_FALLBACK.matcher(content);
        return m.find() ? java.util.Optional.of(m.group(1)) : java.util.Optional.empty();
    }

    private static String tableKind(StoreType storeType) {
        return switch (storeType) {
            case MONGODB -> "COLLECTION";
            case CASSANDRA -> "CQL_TABLE";
            case MARIADB -> "TABLE";
            default -> null;
        };
    }

    static String inferDomainFqn(String entityFqn) {
        if (entityFqn == null || !entityFqn.endsWith("Entity")) return null;
        String withoutEntity = entityFqn.substring(0, entityFqn.length() - "Entity".length());
        int dataIdx = withoutEntity.indexOf(".data.");
        if (dataIdx < 0) return null;
        String prefix = withoutEntity.substring(0, dataIdx);
        String afterData = withoutEntity.substring(dataIdx + ".data.".length());
        String domainSuffix = stripDataTechnologyLayers(afterData);
        if (domainSuffix == null || domainSuffix.isBlank()) return null;
        return prefix + ".domain." + domainSuffix;
    }

    private static String stripDataTechnologyLayers(String afterData) {
        if (afterData.startsWith("rdb.dataaccess.")) {
            return afterData.substring("rdb.dataaccess.".length());
        }
        if (afterData.startsWith("mongo.")) {
            int entityIdx = afterData.lastIndexOf(".entity.");
            if (entityIdx >= 0) {
                return afterData.substring(entityIdx + ".entity.".length());
            }
        }
        int dot = afterData.indexOf('.');
        return dot >= 0 ? afterData.substring(dot + 1) : afterData;
    }

    private static double confidenceFor(
            StoreType storeType, String domainFqn, String physicalName, String content) {
        double c = storeType == StoreType.UNKNOWN ? 0.5 : 0.95;
        if (domainFqn != null) c = Math.min(c, 0.70);
        if (!content.contains("@Table") && !content.contains("@Document")) c = Math.min(c, 0.80);
        return c;
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private static String camelToSnake(String name) {
        if (name.isEmpty()) return name;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c) && i > 0) sb.append('_');
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
