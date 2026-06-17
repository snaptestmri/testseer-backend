package io.testseer.backend.ingestion.catalog;

import io.testseer.backend.query.CatalogResolverService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Tiered FQN resolution for imports, symbol facts, and library catalog (Phase 4). */
@Service
public class TypeFqnResolver {

    private final JdbcClient db;
    private final CatalogResolverService catalogResolver;

    public TypeFqnResolver(JdbcClient db, CatalogResolverService catalogResolver) {
        this.db = db;
        this.catalogResolver = catalogResolver;
    }

    public ResolvedType resolve(String typeName, ImportIndex imports, CompilationContext ctx) {
        if (typeName == null || typeName.isBlank()) {
            return ResolvedType.empty();
        }
        String cleaned = stripGenerics(typeName.trim());
        if (cleaned.contains(".")) {
            return new ResolvedType(cleaned, "QUALIFIED", 0.95);
        }

        if (imports != null) {
            String explicit = imports.resolveExplicit(cleaned);
            if (explicit != null) {
                return new ResolvedType(explicit, "IMPORT", 0.95);
            }
        }

        if (ctx != null && ctx.classpathSimpleNames() != null) {
            String fromClasspath = ctx.classpathSimpleNames().get(cleaned);
            if (fromClasspath != null && !fromClasspath.isBlank()) {
                return new ResolvedType(fromClasspath, "CLASSPATH", 0.90);
            }
        }

        if (ctx != null && ctx.orgId() != null) {
            List<String> pinned = ctx.pinnedCatalogLibraryIds() != null
                    ? ctx.pinnedCatalogLibraryIds() : List.of();
            Optional<String> catalog = catalogResolver.findTypeFqnBySimpleName(ctx.orgId(), pinned, cleaned);
            if (catalog.isPresent()) {
                return new ResolvedType(catalog.get(), "CATALOG", 0.80);
            }
        }

        if (ctx != null && ctx.serviceId() != null) {
            Optional<String> sameService = findSymbolFqn(ctx.orgId(), ctx.serviceId(), cleaned);
            if (sameService.isPresent()) {
                return new ResolvedType(sameService.get(), "SYMBOL_FACT", 0.85);
            }
        }

        String fallback = imports != null
                ? imports.samePackageResolve(cleaned)
                : samePackageFallback(cleaned, ctx != null ? ctx.owningClassFqn() : null);
        return new ResolvedType(fallback, "SAME_PACKAGE", 0.50);
    }

    Optional<String> findSymbolFqn(String orgId, String serviceId, String simpleName) {
        if (orgId == null || serviceId == null || simpleName == null) return Optional.empty();
        try {
            var spec = db.sql("""
                    SELECT sf.symbol_fqn
                    FROM symbol_facts sf
                    JOIN service_registry sr ON sr.service_id = sf.service_id
                    WHERE sr.org_id = :orgId
                      AND sf.service_id = :serviceId
                      AND sf.symbol_fqn LIKE :suffix
                    ORDER BY sf.indexed_at DESC
                    LIMIT 1
                    """);
            if (spec == null) return Optional.empty();
            return spec
                    .param("orgId", orgId)
                    .param("serviceId", serviceId)
                    .param("suffix", "%." + simpleName)
                    .query(String.class)
                    .optional();
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    public static String samePackageFallback(String typeName, String owningClassFqn) {
        if (typeName == null || typeName.isBlank()) return "";
        String cleaned = stripGenerics(typeName);
        if (cleaned.contains(".")) return cleaned;
        int dot = owningClassFqn != null ? owningClassFqn.lastIndexOf('.') : -1;
        if (dot < 0) return cleaned;
        return owningClassFqn.substring(0, dot + 1) + cleaned;
    }

    static String stripGenerics(String typeName) {
        return typeName.replaceAll("<.*>", "").trim();
    }

    public record CompilationContext(
            String orgId,
            String serviceId,
            String owningClassFqn,
            List<String> pinnedCatalogLibraryIds,
            Map<String, String> classpathSimpleNames
    ) {
        public CompilationContext(String orgId, String serviceId, String owningClassFqn) {
            this(orgId, serviceId, owningClassFqn, List.of(), null);
        }

        public CompilationContext(
                String orgId,
                String serviceId,
                String owningClassFqn,
                List<String> pinnedCatalogLibraryIds) {
            this(orgId, serviceId, owningClassFqn, pinnedCatalogLibraryIds, null);
        }
    }

    public record ResolvedType(String fqn, String tier, double confidence) {
        static ResolvedType empty() {
            return new ResolvedType("", "UNKNOWN", 0.0);
        }

        boolean isBlank() {
            return fqn == null || fqn.isBlank();
        }
    }
}
