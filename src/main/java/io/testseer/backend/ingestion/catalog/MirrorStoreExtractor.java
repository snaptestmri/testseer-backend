package io.testseer.backend.ingestion.catalog;

import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.messaging.ProtoSchemaExtractor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class MirrorStoreExtractor {

    private static final Pattern BQ_SYNC_BLOCK =
            Pattern.compile("@LogForBigQuerySync\\s*\\(([^)]*)\\)");
    private static final Pattern TABLE_NAME = Pattern.compile("tableName\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern OPERATION = Pattern.compile("operation\\s*=\\s*Operation\\.(\\w+)");
    private static final Pattern KEY_FIELDS = Pattern.compile("keyFields\\s*=\\s*\\{([^}]*)\\}");
    private static final Pattern METHOD_AFTER =
            Pattern.compile("\\s*(?:public\\s+)?(?:[\\w<>,\\[\\].\\s]+?)\\s+(\\w+)\\s*\\(");

    public List<MirrorRef> extract(List<ProtoSchemaExtractor.JavaSourceFile> javaFiles) {
        List<MirrorRef> mirrors = new ArrayList<>();
        for (ProtoSchemaExtractor.JavaSourceFile file : javaFiles) {
            if (file.classFqn() == null) continue;
            Matcher bm = BQ_SYNC_BLOCK.matcher(file.content());
            while (bm.find()) {
                String block = bm.group(1);
                Matcher tm = TABLE_NAME.matcher(block);
                if (!tm.find()) continue;
                String tableName = tm.group(1);
                String operation = "AUTO";
                Matcher om = OPERATION.matcher(block);
                if (om.find()) operation = om.group(1);
                List<String> keyFields = List.of();
                Matcher km = KEY_FIELDS.matcher(block);
                if (km.find()) {
                    keyFields = Arrays.stream(km.group(1).split(","))
                            .map(s -> s.trim().replace("\"", ""))
                            .filter(s -> !s.isBlank())
                            .collect(Collectors.toList());
                }
                String tail = file.content().substring(bm.end());
                Matcher mm = METHOD_AFTER.matcher(tail);
                if (!mm.find()) continue;
                mirrors.add(new MirrorRef(
                        file.classFqn(), mm.group(1), tableName, operation, keyFields));
            }
        }
        return mirrors;
    }

    public List<FactBatch.DataObjectFact> attachMirrorsToEntities(
            List<FactBatch.DataObjectFact> entities,
            List<MirrorRef> mirrors,
            List<RepoGenericExtractor.RepoLink> repos) {

        Map<String, String> repoToEntity = new LinkedHashMap<>();
        for (RepoGenericExtractor.RepoLink repo : repos) {
            if (repo.entityFqn() != null) repoToEntity.put(repo.accessorFqn(), repo.entityFqn());
        }

        Map<String, FactBatch.DataObjectFact> byEntity = new LinkedHashMap<>();
        for (FactBatch.DataObjectFact e : entities) {
            byEntity.put(e.entityFqn(), e);
        }

        for (MirrorRef mirror : mirrors) {
            String entityFqn = repoToEntity.get(mirror.accessorFqn());
            if (entityFqn == null) {
                entityFqn = entities.stream()
                        .filter(e -> mirror.tableName().equals(e.physicalName()))
                        .map(FactBatch.DataObjectFact::entityFqn)
                        .findFirst().orElse(null);
            }
            if (entityFqn == null) continue;

            FactBatch.DataObjectFact existing = byEntity.get(entityFqn);
            if (existing == null) continue;

            Map<String, Object> mirrorJson = mirror.toAttributesMap();
            String attributes = CatalogAttributesHelper.mergeMirrors(existing.attributes(), List.of(mirrorJson));
            byEntity.put(entityFqn, new FactBatch.DataObjectFact(
                    existing.entityFqn(),
                    existing.domainFqn(),
                    existing.storeType(),
                    existing.physicalName(),
                    existing.catalogOrKeyspace(),
                    existing.collectionOrTableKind(),
                    EvidenceSources.append(existing.evidenceSource(), "BQ_MIRROR"),
                    existing.confidence(),
                    attributes
            ));
        }
        return List.copyOf(byEntity.values());
    }

    public record MirrorRef(
            String accessorFqn,
            String methodName,
            String tableName,
            String operation,
            List<String> keyFields
    ) {
        Map<String, Object> toAttributesMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("storeType", StoreType.BIGQUERY.dbValue());
            map.put("physicalName", tableName);
            map.put("via", "@LogForBigQuerySync");
            map.put("syncMode", "ASYNC_MIRROR");
            map.put("operation", operation);
            map.put("keyFields", keyFields);
            map.put("accessorFqn", accessorFqn);
            map.put("methodName", methodName);
            return map;
        }
    }
}
