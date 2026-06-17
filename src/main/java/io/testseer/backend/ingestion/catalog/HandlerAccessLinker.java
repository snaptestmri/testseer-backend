package io.testseer.backend.ingestion.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.config.WorkspaceCatalogService;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.messaging.ProtoSchemaExtractor;
import io.testseer.backend.query.CatalogResolverService;
import io.testseer.backend.registry.ServiceRegistryService;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class HandlerAccessLinker {

    private static final Pattern ACCESSOR_CALL =
            Pattern.compile("(\\w+(?:Repo|Dao|Repository|Template))\\.(\\w+)\\s*\\(");

    private final CatalogResolverService catalogResolver;
    private final TypeFqnResolver typeFqnResolver;
    private final WorkspaceCatalogService workspaceCatalog;
    private final ServiceRegistryService registryService;
    private final LibraryClasspathBuilder classpathBuilder;
    private final ObjectMapper mapper = new ObjectMapper();

    public HandlerAccessLinker(
            CatalogResolverService catalogResolver,
            TypeFqnResolver typeFqnResolver,
            WorkspaceCatalogService workspaceCatalog,
            ServiceRegistryService registryService,
            LibraryClasspathBuilder classpathBuilder) {
        this.catalogResolver = catalogResolver;
        this.typeFqnResolver = typeFqnResolver;
        this.workspaceCatalog = workspaceCatalog;
        this.registryService = registryService;
        this.classpathBuilder = classpathBuilder;
    }

    public List<FactBatch.DataAccessFact> extract(
            String orgId, String serviceId, List<ProtoSchemaExtractor.JavaSourceFile> javaFiles) {
        String moduleKey = registryService.getById(serviceId).serviceName();
        List<String> pinned = new ArrayList<>(
                workspaceCatalog.pinnedCatalogLibraryIdsForService(orgId, moduleKey));
        if (pinned.isEmpty()) {
            workspaceCatalog.findServiceModule(orgId, moduleKey)
                    .ifPresent(m -> pinned.addAll(workspaceCatalog.pinnedCatalogLibraryIds(m, orgId)));
        }

        var serviceEntry = registryService.getById(serviceId);
        Path githubRoot = workspaceCatalog.resolveGithubRoot(orgId);
        Path serviceRepoRoot = githubRoot != null && serviceEntry != null
                ? githubRoot.resolve(serviceEntry.repo()) : null;
        List<String> serviceSourceRoots = serviceEntry != null
                && serviceEntry.sourceRoots() != null && !serviceEntry.sourceRoots().isEmpty()
                ? serviceEntry.sourceRoots()
                : List.of("src/main/java");
        var symbolCtx = classpathBuilder.forServiceModule(
                orgId, moduleKey, null, serviceRepoRoot, serviceSourceRoots);
        var classpathNames = symbolCtx.classpathSimpleNames();

        List<FactBatch.DataAccessFact> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (ProtoSchemaExtractor.JavaSourceFile file : javaFiles) {
            if (file.classFqn() == null) continue;
            String content = file.content();
            ImportIndex imports = ImportIndex.build(content);
            var ctx = new TypeFqnResolver.CompilationContext(
                    orgId, serviceId, file.classFqn(), pinned, classpathNames);
            FieldTypeIndex fields = FieldTypeIndex.build(content, imports, typeFqnResolver, ctx);

            Matcher m = ACCESSOR_CALL.matcher(content);
            while (m.find()) {
                String fieldName = m.group(1);
                String methodName = m.group(2);
                String handlerMethod = enclosingMethod(content, m.start());
                String accessorFqn = fields.resolveAccessorFqn(fieldName, imports);
                if (accessorFqn == null || accessorFqn.isBlank()) continue;

                String key = file.classFqn() + "|" + handlerMethod + "|" + accessorFqn + "|" + methodName;
                if (!seen.add(key)) continue;

                Optional<CatalogResolverService.AccessorMethodRow> accessor =
                        catalogResolver.findAccessorMethod(orgId, pinned, accessorFqn, methodName);

                String operation = accessor.map(CatalogResolverService.AccessorMethodRow::operation)
                        .orElseGet(() -> DaoMethodExtractor.operationOf(methodName));
                String entityFqn = accessor.map(CatalogResolverService.AccessorMethodRow::entityFqn).orElse(null);
                String domainFqn = accessor.map(CatalogResolverService.AccessorMethodRow::domainFqn).orElse(null);
                String storeType = accessor.map(CatalogResolverService.AccessorMethodRow::storeType).orElse(null);
                String physicalName = accessor.map(CatalogResolverService.AccessorMethodRow::physicalName).orElse(null);
                String catalogRef = null;

                if (entityFqn != null) {
                    Optional<CatalogResolverService.CatalogEntry> entity =
                            catalogResolver.findEntityByFqn(orgId, pinned, entityFqn);
                    if (entity.isPresent()) {
                        storeType = entity.get().storeType();
                        physicalName = entity.get().physicalName();
                        catalogRef = entity.get().catalogOrKeyspace();
                        if (domainFqn == null) domainFqn = entity.get().domainFqn();
                    }
                }

                boolean catalogJoinFailed = accessor.isEmpty() || entityFqn == null;
                if (storeType == null) storeType = inferStoreType(accessorFqn);
                if (physicalName == null) physicalName = inferTableFromAccessor(accessorFqn, methodName);

                double confidence = accessor.isPresent() && entityFqn != null ? 0.93 : 0.80;
                String evidence = accessor.isPresent() ? "HANDLER_LINKER+CATALOG" : "HANDLER_LINKER";
                if (catalogJoinFailed && !pinned.isEmpty()) {
                    evidence = "HANDLER_WITHOUT_CATALOG";
                    confidence = Math.min(confidence, 0.75);
                }
                String secondaryStores = null;
                if (entityFqn != null) {
                    secondaryStores = catalogResolver.findSecondaryStoresJson(
                            orgId, pinned, entityFqn, accessorFqn, methodName).orElse(null);
                }

                results.add(FactBatch.DataAccessFact.linked(
                        file.classFqn(),
                        handlerMethod,
                        operation,
                        storeType,
                        physicalName,
                        fieldName,
                        methodName,
                        correlationKeys(methodName),
                        null,
                        evidence,
                        confidence,
                        entityFqn,
                        domainFqn,
                        accessorFqn,
                        DaoMethodExtractor.accessorKind(accessorFqn),
                        catalogRef,
                        secondaryStores
                ));
            }
        }
        return results;
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

    private static String inferStoreType(String accessorFqn) {
        StoreType hinted = StoreType.fromPackageHint(accessorFqn);
        if (hinted != StoreType.UNKNOWN) {
            return hinted.dbValue();
        }
        if (accessorFqn == null) return StoreType.MARIADB.dbValue();
        String lower = accessorFqn.toLowerCase(Locale.ROOT);
        if (lower.contains(".mongo.") || lower.contains("mongodb")) return StoreType.MONGODB.dbValue();
        if (lower.contains(".nosql.") || lower.contains("cassandra")) return StoreType.CASSANDRA.dbValue();
        if (lower.contains("bigquery")) return StoreType.BIGQUERY.dbValue();
        return StoreType.MARIADB.dbValue();
    }

    private static String inferTableFromAccessor(String accessorFqn, String methodName) {
        if (accessorFqn == null) return methodName;
        int dot = accessorFqn.lastIndexOf('.');
        String simple = dot >= 0 ? accessorFqn.substring(dot + 1) : accessorFqn;
        simple = simple.replace("Dao", "").replace("Repository", "").replace("Repo", "");
        return camelToSnake(simple);
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

    private String correlationKeys(String daoMethod) {
        List<String> keys = new ArrayList<>();
        String lower = daoMethod.toLowerCase(Locale.ROOT);
        if (lower.contains("offerid")) keys.add("offerId");
        if (lower.contains("partnerid")) keys.add("partnerId");
        if (keys.isEmpty()) keys.add("offerId");
        try {
            return mapper.writeValueAsString(keys);
        } catch (JsonProcessingException ex) {
            return "[\"offerId\"]";
        }
    }
}
