package io.testseer.backend.query;

import io.testseer.backend.config.WorkspaceCatalogService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Cross-service catalog lookup with pinned library joins (JOIN-CAT-01). */
@Service
public class CatalogResolverService {

    private final JdbcClient db;
    private final WorkspaceCatalogService workspaceCatalog;
    private final int staleThresholdMinutes;

    public CatalogResolverService(
            JdbcClient db,
            WorkspaceCatalogService workspaceCatalog,
            @Value("${testseer.stale-threshold-minutes:60}") int staleThresholdMinutes) {
        this.db = db;
        this.workspaceCatalog = workspaceCatalog;
        this.staleThresholdMinutes = staleThresholdMinutes;
    }

    public Optional<AccessorMethodRow> findAccessorMethod(String orgId, String accessorFqn, String methodName) {
        return findAccessorMethod(orgId, List.of(), accessorFqn, methodName);
    }

    public Optional<AccessorMethodRow> findAccessorMethod(
            String orgId, List<String> pinnedLibraryServiceIds, String accessorFqn, String methodName) {
        if (accessorFqn == null || methodName == null) return Optional.empty();
        if (pinnedLibraryServiceIds != null && !pinnedLibraryServiceIds.isEmpty()) {
            for (String libraryId : pinnedLibraryServiceIds) {
                Optional<AccessorMethodRow> pinned = findAccessorMethodPinned(orgId, libraryId, accessorFqn, methodName);
                if (pinned.isPresent()) return pinned;
            }
            return Optional.empty();
        }
        return findAccessorMethodOrgWide(orgId, accessorFqn, methodName);
    }

    private Optional<AccessorMethodRow> findAccessorMethodPinned(
            String orgId, String libraryKey, String accessorFqn, String methodName) {
        return db.sql("""
                SELECT am.accessor_fqn, am.method_name, am.operation, am.entity_fqn, am.domain_fqn,
                       am.store_type, am.physical_name, am.confidence
                FROM accessor_method_facts am
                JOIN service_registry sr ON sr.service_id = am.service_id
                WHERE sr.org_id = :orgId
                  AND sr.service_name = :libraryKey
                  AND sr.module_type = 'library'
                  AND am.accessor_fqn = :accessorFqn
                  AND am.method_name = :methodName
                ORDER BY am.indexed_at DESC
                LIMIT 1
                """)
                .param("orgId", orgId)
                .param("libraryKey", libraryKey)
                .param("accessorFqn", accessorFqn)
                .param("methodName", methodName)
                .query((rs, row) -> new AccessorMethodRow(
                        rs.getString("accessor_fqn"),
                        rs.getString("method_name"),
                        rs.getString("operation"),
                        rs.getString("entity_fqn"),
                        rs.getString("domain_fqn"),
                        rs.getString("store_type"),
                        rs.getString("physical_name"),
                        null,
                        rs.getDouble("confidence")
                ))
                .optional();
    }

    private Optional<AccessorMethodRow> findAccessorMethodOrgWide(
            String orgId, String accessorFqn, String methodName) {
        return db.sql("""
                SELECT am.accessor_fqn, am.method_name, am.operation, am.entity_fqn, am.domain_fqn,
                       am.store_type, am.physical_name, am.confidence
                FROM accessor_method_facts am
                JOIN service_registry sr ON sr.service_id = am.service_id
                WHERE sr.org_id = :orgId
                  AND am.accessor_fqn = :accessorFqn
                  AND am.method_name = :methodName
                ORDER BY am.indexed_at DESC
                LIMIT 1
                """)
                .param("orgId", orgId)
                .param("accessorFqn", accessorFqn)
                .param("methodName", methodName)
                .query((rs, row) -> new AccessorMethodRow(
                        rs.getString("accessor_fqn"),
                        rs.getString("method_name"),
                        rs.getString("operation"),
                        rs.getString("entity_fqn"),
                        rs.getString("domain_fqn"),
                        rs.getString("store_type"),
                        rs.getString("physical_name"),
                        null,
                        rs.getDouble("confidence")
                ))
                .optional();
    }

    public Optional<CatalogEntry> findEntityByFqn(String orgId, String entityFqn) {
        return findEntityByFqn(orgId, List.of(), entityFqn);
    }

    public Optional<CatalogEntry> findEntityByFqn(
            String orgId, List<String> pinnedLibraryServiceIds, String entityFqn) {
        if (entityFqn == null) return Optional.empty();
        if (pinnedLibraryServiceIds != null && !pinnedLibraryServiceIds.isEmpty()) {
            for (String libraryId : pinnedLibraryServiceIds) {
                Optional<CatalogEntry> pinned = findEntityByFqnPinned(orgId, libraryId, entityFqn);
                if (pinned.isPresent()) return pinned;
            }
            return Optional.empty();
        }
        return findEntityByFqnOrgWide(orgId, entityFqn);
    }

    private Optional<CatalogEntry> findEntityByFqnPinned(String orgId, String libraryKey, String entityFqn) {
        return db.sql("""
                SELECT d.entity_fqn, d.domain_fqn, d.store_type, d.physical_name,
                       d.catalog_or_keyspace, d.confidence, d.attributes
                FROM data_object_facts d
                JOIN service_registry sr ON sr.service_id = d.service_id
                WHERE sr.org_id = :orgId
                  AND sr.service_name = :libraryKey
                  AND sr.module_type = 'library'
                  AND d.entity_fqn = :entityFqn
                ORDER BY d.indexed_at DESC
                LIMIT 1
                """)
                .param("orgId", orgId)
                .param("libraryKey", libraryKey)
                .param("entityFqn", entityFqn)
                .query((rs, row) -> new CatalogEntry(
                        rs.getString("entity_fqn"),
                        rs.getString("domain_fqn"),
                        rs.getString("store_type"),
                        rs.getString("physical_name"),
                        rs.getString("catalog_or_keyspace"),
                        rs.getDouble("confidence"),
                        rs.getString("attributes")
                ))
                .optional();
    }

    private Optional<CatalogEntry> findEntityByFqnOrgWide(String orgId, String entityFqn) {
        return db.sql("""
                SELECT d.entity_fqn, d.domain_fqn, d.store_type, d.physical_name,
                       d.catalog_or_keyspace, d.confidence, d.attributes
                FROM data_object_facts d
                JOIN service_registry sr ON sr.service_id = d.service_id
                WHERE sr.org_id = :orgId AND d.entity_fqn = :entityFqn
                ORDER BY d.indexed_at DESC
                LIMIT 1
                """)
                .param("orgId", orgId)
                .param("entityFqn", entityFqn)
                .query((rs, row) -> new CatalogEntry(
                        rs.getString("entity_fqn"),
                        rs.getString("domain_fqn"),
                        rs.getString("store_type"),
                        rs.getString("physical_name"),
                        rs.getString("catalog_or_keyspace"),
                        rs.getDouble("confidence"),
                        rs.getString("attributes")
                ))
                .optional();
    }

    public Optional<String> findSecondaryStoresJson(
            String orgId, String entityFqn, String accessorFqn, String methodName) {
        return findSecondaryStoresJson(orgId, List.of(), entityFqn, accessorFqn, methodName);
    }

    public Optional<String> findSecondaryStoresJson(
            String orgId,
            List<String> pinnedLibraryServiceIds,
            String entityFqn,
            String accessorFqn,
            String methodName) {
        return findEntityByFqn(orgId, pinnedLibraryServiceIds, entityFqn)
                .map(CatalogEntry::attributes)
                .map(attrs -> io.testseer.backend.ingestion.catalog.CatalogAttributesHelper
                        .secondaryStoresForMethod(attrs, accessorFqn, methodName))
                .filter(json -> json != null && !json.isBlank());
    }

    public Optional<String> findTypeFqnBySimpleName(String orgId, String simpleName) {
        return findTypeFqnBySimpleName(orgId, List.of(), simpleName);
    }

    public Optional<String> findTypeFqnBySimpleName(
            String orgId, List<String> pinnedLibraryServiceIds, String simpleName) {
        if (orgId == null || simpleName == null || simpleName.isBlank()) return Optional.empty();
        String suffix = "%." + simpleName;

        if (pinnedLibraryServiceIds != null && !pinnedLibraryServiceIds.isEmpty()) {
            for (String libraryId : pinnedLibraryServiceIds) {
                Optional<String> fromAccessor = db.sql("""
                        SELECT am.accessor_fqn
                        FROM accessor_method_facts am
                        JOIN service_registry sr ON sr.service_id = am.service_id
                        WHERE sr.org_id = :orgId
                          AND sr.service_name = :libraryKey
                          AND sr.module_type = 'library'
                          AND am.accessor_fqn LIKE :suffix
                        ORDER BY am.indexed_at DESC
                        LIMIT 1
                        """)
                        .param("orgId", orgId)
                        .param("libraryKey", libraryId)
                        .param("suffix", suffix)
                        .query(String.class)
                        .optional();
                if (fromAccessor.isPresent()) return fromAccessor;
            }
        }

        Optional<String> fromAccessor = db.sql("""
                SELECT am.accessor_fqn
                FROM accessor_method_facts am
                JOIN service_registry sr ON sr.service_id = am.service_id
                WHERE sr.org_id = :orgId AND am.accessor_fqn LIKE :suffix
                ORDER BY am.indexed_at DESC
                LIMIT 1
                """)
                .param("orgId", orgId)
                .param("suffix", suffix)
                .query(String.class)
                .optional();
        if (fromAccessor.isPresent()) return fromAccessor;

        return db.sql("""
                SELECT sf.symbol_fqn
                FROM symbol_facts sf
                JOIN service_registry sr ON sr.service_id = sf.service_id
                WHERE sr.org_id = :orgId
                  AND sr.module_type = 'library'
                  AND sf.symbol_fqn LIKE :suffix
                ORDER BY sf.indexed_at DESC
                LIMIT 1
                """)
                .param("orgId", orgId)
                .param("suffix", suffix)
                .query(String.class)
                .optional();
    }

    public boolean isAnyPinnedLibraryStale(String orgId, List<String> pinnedLibraryServiceIds) {
        if (pinnedLibraryServiceIds == null || pinnedLibraryServiceIds.isEmpty()) return false;
        for (String libraryId : pinnedLibraryServiceIds) {
            if (isLibraryStale(orgId, libraryId)) return true;
        }
        return false;
    }

    public boolean isLibraryStale(String orgId, String libraryKey) {
        return db.sql("""
                SELECT MAX(d.indexed_at) AS last_indexed
                FROM data_object_facts d
                JOIN service_registry sr ON sr.service_id = d.service_id
                WHERE sr.org_id = :orgId AND sr.service_name = :libraryKey
                """)
                .param("orgId", orgId)
                .param("libraryKey", libraryKey)
                .query((rs, row) -> rs.getTimestamp("last_indexed"))
                .optional()
                .map(ts -> ts == null || Duration.between(ts.toInstant(), Instant.now()).toMinutes()
                        > staleThresholdMinutes)
                .orElse(true);
    }

    public record AccessorMethodRow(
            String accessorFqn,
            String methodName,
            String operation,
            String entityFqn,
            String domainFqn,
            String storeType,
            String physicalName,
            String catalogOrKeyspace,
            double confidence
    ) {}

    public record CatalogEntry(
            String entityFqn,
            String domainFqn,
            String storeType,
            String physicalName,
            String catalogOrKeyspace,
            double confidence,
            String attributes
    ) {}
}
