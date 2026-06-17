package io.testseer.backend.ingestion.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.messaging.ProtoSchemaExtractor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RepoGenericExtractor {

    private static final Pattern INTERFACE_DECL =
            Pattern.compile("(?:public\\s+)?interface\\s+(\\w+)([^{]*)\\{");
    private static final Pattern ENTITY_GENERIC =
            Pattern.compile("(?:JpaRepository|MongoRepository|CassandraRepository|BaseNoSqlRepository)\\s*<\\s*(\\w+)");

    private final ObjectMapper mapper = new ObjectMapper();
    private final StoreTypeInferencer storeTypeInferencer;

    public RepoGenericExtractor(StoreTypeInferencer storeTypeInferencer) {
        this.storeTypeInferencer = storeTypeInferencer;
    }

    public List<RepoLink> extract(List<ProtoSchemaExtractor.JavaSourceFile> javaFiles) {
        List<RepoLink> links = new ArrayList<>();
        for (ProtoSchemaExtractor.JavaSourceFile file : javaFiles) {
            if (file.classFqn() == null) continue;
            Matcher im = INTERFACE_DECL.matcher(file.content());
            if (!im.find()) continue;
            String extendsClause = im.group(2);
            Matcher gm = ENTITY_GENERIC.matcher(extendsClause);
            if (!gm.find()) continue;
            String entitySimple = gm.group(1);
            String entityFqn = resolveEntityFqn(file.content(), file.classFqn(), entitySimple);
            StoreType storeType = storeTypeInferencer.inferFromRepoInterface(file.classFqn(), extendsClause);
            links.add(new RepoLink(file.classFqn(), entityFqn, entitySimple, storeType));
        }
        return links;
    }

    /** Merge repo accessor refs into data object attributes JSON on matching entity rows. */
    public List<FactBatch.DataObjectFact> enrichWithRepos(
            List<FactBatch.DataObjectFact> entities,
            List<RepoLink> repos) {
        Map<String, FactBatch.DataObjectFact> byEntity = new LinkedHashMap<>();
        for (FactBatch.DataObjectFact e : entities) {
            byEntity.put(e.entityFqn(), e);
        }
        for (RepoLink repo : repos) {
            if (repo.entityFqn() == null) continue;
            FactBatch.DataObjectFact existing = byEntity.get(repo.entityFqn());
            if (existing == null) continue;
            String attributes = mergeAccessor(existing.attributes(), repo.accessorFqn(), repo.storeType());
            byEntity.put(repo.entityFqn(), new FactBatch.DataObjectFact(
                    existing.entityFqn(),
                    existing.domainFqn(),
                    existing.storeType(),
                    existing.physicalName(),
                    existing.catalogOrKeyspace(),
                    existing.collectionOrTableKind(),
                    EvidenceSources.append(existing.evidenceSource(), "REPO_GENERIC"),
                    Math.max(existing.confidence(), 0.92),
                    attributes
            ));
        }
        return List.copyOf(byEntity.values());
    }

    private static String resolveEntityFqn(String content, String repoFqn, String entitySimple) {
        Matcher importMatcher = Pattern.compile(
                "import\\s+([\\w.]+\\." + Pattern.quote(entitySimple) + ")\\s*;").matcher(content);
        if (importMatcher.find()) return importMatcher.group(1);
        int dot = repoFqn.lastIndexOf('.');
        if (dot < 0) return entitySimple;
        return repoFqn.substring(0, dot + 1) + entitySimple;
    }

    private String mergeAccessor(String existingJson, String accessorFqn, StoreType storeType) {
        try {
            Map<String, Object> root = existingJson != null && !existingJson.isBlank()
                    ? mapper.readValue(existingJson, Map.class)
                    : new LinkedHashMap<>();
            @SuppressWarnings("unchecked")
            List<Map<String, String>> accessors = (List<Map<String, String>>) root.computeIfAbsent(
                    "accessors", k -> new ArrayList<>());
            Map<String, String> ref = new LinkedHashMap<>();
            ref.put("kind", "REPO");
            ref.put("accessorFqn", accessorFqn);
            ref.put("storeType", storeType.dbValue());
            accessors.add(ref);
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            return existingJson;
        }
    }

    public record RepoLink(String accessorFqn, String entityFqn, String entitySimple, StoreType storeType) {}
}
