package io.testseer.backend.ingestion.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.messaging.ProtoSchemaExtractor;
import io.testseer.backend.query.CatalogResolverService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MongoAccessExtractor {

    private static final Pattern MONGO_CALL =
            Pattern.compile("(\\w+(?:Template|Repo|Repository))\\.(save|insert|find\\w*|delete\\w*|remove\\w*|update\\w*)\\s*\\(");

    private final CatalogResolverService catalogResolver;
    private final TypeFqnResolver typeFqnResolver;
    private final ObjectMapper mapper = new ObjectMapper();

    public MongoAccessExtractor(CatalogResolverService catalogResolver, TypeFqnResolver typeFqnResolver) {
        this.catalogResolver = catalogResolver;
        this.typeFqnResolver = typeFqnResolver;
    }

    public List<FactBatch.DataAccessFact> extract(
            String orgId, String serviceId, List<ProtoSchemaExtractor.JavaSourceFile> javaFiles) {
        List<FactBatch.DataAccessFact> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (ProtoSchemaExtractor.JavaSourceFile file : javaFiles) {
            if (file.classFqn() == null) continue;
            String content = file.content();
            ImportIndex imports = ImportIndex.build(content);
            var ctx = new TypeFqnResolver.CompilationContext(orgId, serviceId, file.classFqn());
            FieldTypeIndex fields = FieldTypeIndex.build(content, imports, typeFqnResolver, ctx);

            Matcher m = MONGO_CALL.matcher(content);
            while (m.find()) {
                String fieldName = m.group(1);
                String methodName = m.group(2);
                String accessorFqn = fields.resolveAccessorFqn(fieldName, imports);
                if (accessorFqn == null) continue;
                if (!isMongoAccessor(accessorFqn, fieldName, imports)) continue;

                String handlerMethod = enclosingMethod(content, m.start());
                String key = file.classFqn() + "|" + handlerMethod + "|" + accessorFqn + "|" + methodName;
                if (!seen.add(key)) continue;

                Optional<CatalogResolverService.AccessorMethodRow> accessor =
                        catalogResolver.findAccessorMethod(orgId, accessorFqn, methodName);
                String entityFqn = accessor.map(CatalogResolverService.AccessorMethodRow::entityFqn).orElse(null);
                String physicalName = accessor.map(CatalogResolverService.AccessorMethodRow::physicalName).orElse(null);
                String domainFqn = accessor.map(CatalogResolverService.AccessorMethodRow::domainFqn).orElse(null);

                if (entityFqn != null && physicalName == null) {
                    physicalName = catalogResolver.findEntityByFqn(orgId, entityFqn)
                            .map(CatalogResolverService.CatalogEntry::physicalName).orElse(null);
                }
                if (physicalName == null) {
                    physicalName = inferCollectionFromAccessor(accessorFqn);
                }

                results.add(FactBatch.DataAccessFact.linked(
                        file.classFqn(),
                        handlerMethod,
                        DaoMethodExtractor.operationOf(methodName),
                        StoreType.MONGODB.dbValue(),
                        physicalName,
                        fieldName,
                        methodName,
                        correlationKeys(methodName),
                        null,
                        entityFqn != null ? "MONGO_ACCESS+CATALOG" : "MONGO_ACCESS",
                        entityFqn != null ? 0.90 : 0.78,
                        entityFqn,
                        domainFqn,
                        accessorFqn,
                        DaoMethodExtractor.accessorKind(accessorFqn),
                        null,
                        null
                ));
            }
        }
        return results;
    }

    private boolean isMongoAccessor(String accessorFqn, String fieldName, ImportIndex imports) {
        if (accessorFqn.toLowerCase(Locale.ROOT).contains("mongo")) return true;
        String type = imports.resolve(fieldName);
        return type != null && type.toLowerCase(Locale.ROOT).contains("mongo");
    }

    private static String inferCollectionFromAccessor(String accessorFqn) {
        int dot = accessorFqn.lastIndexOf('.');
        String simple = dot >= 0 ? accessorFqn.substring(dot + 1) : accessorFqn;
        simple = simple.replace("Repository", "").replace("Repo", "");
        return camelToSnake(simple);
    }

    private static String enclosingMethod(String content, int callIndex) {
        Matcher m = Pattern.compile("(public|protected)\\s+[\\w<>,\\[\\].\\s]+\\s+(\\w+)\\s*\\(").matcher(content);
        String last = null;
        while (m.find()) {
            if (m.start() < callIndex) {
                String name = m.group(2);
                if (!name.equals("class") && !name.startsWith("get") && !name.startsWith("set")) {
                    last = name;
                }
            }
        }
        return last;
    }

    private static String camelToSnake(String name) {
        if (name == null || name.isEmpty()) return name;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c) && i > 0) sb.append('_');
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private String correlationKeys(String methodName) {
        try {
            return mapper.writeValueAsString(List.of("offerId"));
        } catch (JsonProcessingException ex) {
            return "[\"offerId\"]";
        }
    }
}
