package io.testseer.backend.query;

import io.testseer.backend.config.WorkspaceCatalogService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Phase 5 + GAP-CAT: drift report for catalog vs DDL and library join gaps. */
@Service
public class DataObjectGapService {

    private final JdbcClient db;
    private final WorkspaceCatalogService workspaceCatalog;
    private final CatalogResolverService catalogResolver;

    public DataObjectGapService(
            JdbcClient db,
            WorkspaceCatalogService workspaceCatalog,
            CatalogResolverService catalogResolver) {
        this.db = db;
        this.workspaceCatalog = workspaceCatalog;
        this.catalogResolver = catalogResolver;
    }

    public List<DataObjectGapView> computeGaps(String orgId) {
        List<DataObjectGapView> gaps = new ArrayList<>();
        gaps.addAll(inferredNotInDdl(orgId));
        gaps.addAll(ddlUnreferenced(orgId));
        gaps.addAll(libraryNotIndexed(orgId));
        gaps.addAll(handlerWithoutCatalog(orgId));
        return gaps;
    }

    private List<DataObjectGapView> libraryNotIndexed(String orgId) {
        List<DataObjectGapView> gaps = new ArrayList<>();
        if (workspaceCatalog.config(orgId).catalogLibraries() == null) return gaps;
        for (var lib : workspaceCatalog.config(orgId).catalogLibraries()) {
            boolean indexed = db.sql("""
                    SELECT COUNT(*) FROM service_registry sr
                    WHERE sr.org_id = :orgId
                      AND sr.service_name = :serviceName
                      AND (
                        EXISTS (SELECT 1 FROM data_object_facts d WHERE d.service_id = sr.service_id)
                        OR EXISTS (SELECT 1 FROM symbol_facts s WHERE s.service_id = sr.service_id)
                      )
                    """)
                    .param("orgId", orgId)
                    .param("serviceName", lib.serviceName() != null ? lib.serviceName() : lib.id())
                    .query(Integer.class)
                    .single() > 0;
            if (!indexed) {
                gaps.add(new DataObjectGapView(
                        "LIBRARY_NOT_INDEXED",
                        null,
                        lib.id(),
                        null,
                        lib.repo(),
                        "Pinned catalog library has no indexed data_object_facts"));
            } else if (catalogResolver.isLibraryStale(orgId, lib.id())) {
                gaps.add(new DataObjectGapView(
                        "LIBRARY_STALE",
                        null,
                        lib.id(),
                        null,
                        lib.repo(),
                        "Pinned catalog library index is older than stale threshold"));
            }
        }
        return gaps;
    }

    private List<DataObjectGapView> handlerWithoutCatalog(String orgId) {
        return db.sql("""
                SELECT DISTINCT handler_class_fqn, handler_method, accessor_fqn, dao_method
                FROM data_access_facts
                WHERE org_id = :orgId
                  AND evidence_source = 'HANDLER_WITHOUT_CATALOG'
                ORDER BY handler_class_fqn, handler_method
                """)
                .param("orgId", orgId)
                .query((rs, row) -> new DataObjectGapView(
                        "HANDLER_WITHOUT_CATALOG",
                        null,
                        rs.getString("accessor_fqn"),
                        rs.getString("handler_class_fqn"),
                        rs.getString("dao_method"),
                        "Handler touchpoint missing pinned catalog join for " + rs.getString("handler_method")
                ))
                .list();
    }

    private List<DataObjectGapView> inferredNotInDdl(String orgId) {
        return db.sql("""
                SELECT DISTINCT d.store_type, d.physical_name, d.entity_fqn, d.catalog_or_keyspace
                FROM data_object_facts d
                WHERE d.org_id = :orgId
                  AND d.store_type IN ('MARIADB', 'CASSANDRA')
                  AND NOT EXISTS (
                      SELECT 1 FROM schema_object_facts s
                      WHERE s.org_id = d.org_id
                        AND s.store_type = d.store_type
                        AND s.physical_name = d.physical_name
                  )
                ORDER BY d.store_type, d.physical_name
                """)
                .param("orgId", orgId)
                .query((rs, row) -> new DataObjectGapView(
                        "INFERRED_NOT_IN_DDL",
                        rs.getString("store_type"),
                        rs.getString("physical_name"),
                        rs.getString("entity_fqn"),
                        null,
                        "Catalog entity has no matching DDL in schema_object_facts"
                ))
                .list();
    }

    private List<DataObjectGapView> ddlUnreferenced(String orgId) {
        return db.sql("""
                SELECT DISTINCT s.store_type, s.physical_name, s.catalog_or_keyspace, s.ddl_path
                FROM schema_object_facts s
                WHERE s.org_id = :orgId
                  AND NOT EXISTS (
                      SELECT 1 FROM data_object_facts d
                      WHERE d.org_id = s.org_id
                        AND d.store_type = s.store_type
                        AND d.physical_name = s.physical_name
                  )
                ORDER BY s.store_type, s.physical_name
                """)
                .param("orgId", orgId)
                .query((rs, row) -> new DataObjectGapView(
                        "DDL_UNREFERENCED",
                        rs.getString("store_type"),
                        rs.getString("physical_name"),
                        null,
                        rs.getString("ddl_path"),
                        "DDL table has no matching data_object_facts catalog entry"
                ))
                .list();
    }

    public record DataObjectGapView(
            String gapType,
            String storeType,
            String physicalName,
            String entityFqn,
            String ddlPath,
            String description
    ) {}
}