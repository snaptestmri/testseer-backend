package io.testseer.backend.config.workspace;

import io.testseer.backend.config.WorkspaceConfig;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class OrgWorkspaceConfigRepository {

    private final JdbcClient db;

    public OrgWorkspaceConfigRepository(JdbcClient db) {
        this.db = db;
    }

    public boolean hasCatalogLibraries(String orgId) {
        return db.sql("""
                SELECT COUNT(*) FROM workspace_catalog_library WHERE org_id = :orgId
                """)
                .param("orgId", orgId)
                .query(Integer.class)
                .single() > 0;
    }

    public boolean hasServiceModules(String orgId) {
        return db.sql("""
                SELECT COUNT(*) FROM workspace_service_module WHERE org_id = :orgId
                """)
                .param("orgId", orgId)
                .query(Integer.class)
                .single() > 0;
    }

    public boolean hasBundles(String orgId) {
        return db.sql("""
                SELECT COUNT(*) FROM workspace_bundle WHERE org_id = :orgId
                """)
                .param("orgId", orgId)
                .query(Integer.class)
                .single() > 0;
    }

    public Optional<OrgSettingsRow> findOrgSettings(String orgId) {
        return db.sql("SELECT * FROM workspace_org_settings WHERE org_id = :orgId")
                .param("orgId", orgId)
                .query(this::mapOrgSettings)
                .optional();
    }

    public void upsertOrgSettings(String orgId, String githubDir, String defaultBundle) {
        db.sql("""
                INSERT INTO workspace_org_settings (org_id, github_dir, default_bundle)
                VALUES (:orgId, :githubDir, :defaultBundle)
                ON CONFLICT (org_id) DO UPDATE SET
                  github_dir     = COALESCE(EXCLUDED.github_dir, workspace_org_settings.github_dir),
                  default_bundle = COALESCE(EXCLUDED.default_bundle, workspace_org_settings.default_bundle),
                  updated_at     = now()
                """)
                .param("orgId", orgId)
                .param("githubDir", githubDir)
                .param("defaultBundle", defaultBundle)
                .update();
    }

    public List<WorkspaceConfig.CatalogLibraryConfig> listCatalogLibraries(String orgId) {
        return db.sql("""
                SELECT * FROM workspace_catalog_library
                WHERE org_id = :orgId
                ORDER BY library_id
                """)
                .param("orgId", orgId)
                .query(this::mapCatalogLibrary)
                .list();
    }

    public Optional<WorkspaceConfig.CatalogLibraryConfig> findCatalogLibrary(String orgId, String libraryId) {
        return db.sql("""
                SELECT * FROM workspace_catalog_library
                WHERE org_id = :orgId AND library_id = :libraryId
                """)
                .param("orgId", orgId)
                .param("libraryId", libraryId)
                .query(this::mapCatalogLibrary)
                .optional();
    }

    public Optional<WorkspaceConfig.CatalogLibraryConfig> findCatalogLibraryByRepo(String orgId, String repo) {
        return db.sql("""
                SELECT * FROM workspace_catalog_library
                WHERE org_id = :orgId AND repo = :repo
                ORDER BY library_id
                LIMIT 1
                """)
                .param("orgId", orgId)
                .param("repo", repo)
                .query(this::mapCatalogLibrary)
                .optional();
    }

    public void insertCatalogLibrary(String orgId, CatalogLibraryUpsert upsert) {
        db.sql("""
                INSERT INTO workspace_catalog_library
                  (org_id, library_id, repo, service_name, source_roots, index_ddl)
                VALUES (:orgId, :libraryId, :repo, :serviceName, :sourceRoots::text[], :indexDdl)
                """)
                .param("orgId", orgId)
                .param("libraryId", upsert.id())
                .param("repo", upsert.repo())
                .param("serviceName", upsert.serviceName())
                .param("sourceRoots", toArrayLiteral(upsert.sourceRoots()))
                .param("indexDdl", upsert.indexDdl())
                .update();
    }

    public int updateCatalogLibrary(String orgId, String libraryId, CatalogLibraryUpsert upsert) {
        return db.sql("""
                UPDATE workspace_catalog_library SET
                  repo         = COALESCE(:repo, repo),
                  service_name = COALESCE(:serviceName, service_name),
                  source_roots = COALESCE(:sourceRoots::text[], source_roots),
                  index_ddl    = COALESCE(:indexDdl, index_ddl),
                  updated_at   = now()
                WHERE org_id = :orgId AND library_id = :libraryId
                """)
                .param("orgId", orgId)
                .param("libraryId", libraryId)
                .param("repo", upsert.repo())
                .param("serviceName", upsert.serviceName())
                .param("sourceRoots", upsert.sourceRoots() != null ? toArrayLiteral(upsert.sourceRoots()) : null)
                .param("indexDdl", upsert.indexDdl())
                .update();
    }

    public int deleteCatalogLibrary(String orgId, String libraryId) {
        return db.sql("""
                DELETE FROM workspace_catalog_library
                WHERE org_id = :orgId AND library_id = :libraryId
                """)
                .param("orgId", orgId)
                .param("libraryId", libraryId)
                .update();
    }

    public List<WorkspaceConfig.ServiceModuleConfig> listServiceModules(String orgId) {
        List<ServiceModuleBase> modules = db.sql("""
                SELECT * FROM workspace_service_module
                WHERE org_id = :orgId
                ORDER BY module_id
                """)
                .param("orgId", orgId)
                .query(this::mapServiceModuleBase)
                .list();
        Map<String, List<WorkspaceConfig.SymbolClasspathEntry>> classpath = loadSymbolClasspath(orgId);
        return modules.stream()
                .map(m -> new WorkspaceConfig.ServiceModuleConfig(
                        m.id(), m.repo(), m.sourceRoots(),
                        classpath.getOrDefault(m.id(), List.of())))
                .toList();
    }

    public Optional<WorkspaceConfig.ServiceModuleConfig> findServiceModule(String orgId, String moduleId) {
        return db.sql("""
                SELECT * FROM workspace_service_module
                WHERE org_id = :orgId AND module_id = :moduleId
                """)
                .param("orgId", orgId)
                .param("moduleId", moduleId)
                .query(this::mapServiceModuleBase)
                .optional()
                .map(m -> new WorkspaceConfig.ServiceModuleConfig(
                        m.id(), m.repo(), m.sourceRoots(),
                        loadSymbolClasspathForModule(orgId, moduleId)));
    }

    public Optional<WorkspaceConfig.ServiceModuleConfig> findServiceModuleByRepo(String orgId, String repo) {
        return db.sql("""
                SELECT * FROM workspace_service_module
                WHERE org_id = :orgId AND repo = :repo
                ORDER BY module_id
                LIMIT 1
                """)
                .param("orgId", orgId)
                .param("repo", repo)
                .query(this::mapServiceModuleBase)
                .optional()
                .map(m -> new WorkspaceConfig.ServiceModuleConfig(
                        m.id(), m.repo(), m.sourceRoots(),
                        loadSymbolClasspathForModule(orgId, m.id())));
    }

    public void insertServiceModule(String orgId, ServiceModuleUpsert upsert) {
        db.sql("""
                INSERT INTO workspace_service_module
                  (org_id, module_id, repo, source_roots)
                VALUES (:orgId, :moduleId, :repo, :sourceRoots::text[])
                """)
                .param("orgId", orgId)
                .param("moduleId", upsert.id())
                .param("repo", upsert.repo())
                .param("sourceRoots", toArrayLiteral(upsert.sourceRoots()))
                .update();
        replaceSymbolClasspath(orgId, upsert.id(), upsert.catalogLibraryIds());
    }

    public int updateServiceModule(String orgId, String moduleId, ServiceModuleUpsert upsert) {
        int updated = db.sql("""
                UPDATE workspace_service_module SET
                  repo         = COALESCE(:repo, repo),
                  source_roots = COALESCE(:sourceRoots::text[], source_roots),
                  updated_at   = now()
                WHERE org_id = :orgId AND module_id = :moduleId
                """)
                .param("orgId", orgId)
                .param("moduleId", moduleId)
                .param("repo", upsert.repo())
                .param("sourceRoots", upsert.sourceRoots() != null ? toArrayLiteral(upsert.sourceRoots()) : null)
                .update();
        if (upsert.catalogLibraryIds() != null) {
            replaceSymbolClasspath(orgId, moduleId, upsert.catalogLibraryIds());
        }
        return updated;
    }

    public int deleteServiceModule(String orgId, String moduleId) {
        return db.sql("""
                DELETE FROM workspace_service_module
                WHERE org_id = :orgId AND module_id = :moduleId
                """)
                .param("orgId", orgId)
                .param("moduleId", moduleId)
                .update();
    }

    public Map<String, WorkspaceConfig.BundleConfig> listBundles(String orgId) {
        List<BundleRow> bundles = db.sql("""
                SELECT * FROM workspace_bundle WHERE org_id = :orgId ORDER BY bundle_name
                """)
                .param("orgId", orgId)
                .query(this::mapBundleRow)
                .list();
        Map<String, WorkspaceConfig.BundleConfig> out = new LinkedHashMap<>();
        for (BundleRow bundle : bundles) {
            List<WorkspaceConfig.BundleIndexEntry> indexOrder = loadBundleIndexOrder(orgId, bundle.bundleName());
            WorkspaceConfig.TraceConfig trace = bundle.traceShortId() != null || bundle.traceEnv() != null
                    ? new WorkspaceConfig.TraceConfig(bundle.traceShortId(), bundle.traceEnv())
                    : null;
            out.put(bundle.bundleName(), new WorkspaceConfig.BundleConfig(List.of(), indexOrder, trace, List.of()));
        }
        return out;
    }

    public void upsertBundle(String orgId, BundleUpsert upsert) {
        db.sql("""
                INSERT INTO workspace_bundle (org_id, bundle_name, trace_short_id, trace_env)
                VALUES (:orgId, :bundleName, :traceShortId, :traceEnv)
                ON CONFLICT (org_id, bundle_name) DO UPDATE SET
                  trace_short_id = COALESCE(EXCLUDED.trace_short_id, workspace_bundle.trace_short_id),
                  trace_env      = COALESCE(EXCLUDED.trace_env, workspace_bundle.trace_env),
                  updated_at     = now()
                """)
                .param("orgId", orgId)
                .param("bundleName", upsert.bundleName())
                .param("traceShortId", upsert.traceShortId())
                .param("traceEnv", upsert.traceEnv())
                .update();
        if (upsert.indexOrder() != null) {
            replaceBundleIndexOrder(orgId, upsert.bundleName(), upsert.indexOrder());
        }
    }

    public int deleteBundle(String orgId, String bundleName) {
        return db.sql("""
                DELETE FROM workspace_bundle WHERE org_id = :orgId AND bundle_name = :bundleName
                """)
                .param("orgId", orgId)
                .param("bundleName", bundleName)
                .update();
    }

    private void replaceSymbolClasspath(String orgId, String moduleId, List<String> catalogLibraryIds) {
        db.sql("""
                DELETE FROM workspace_symbol_classpath
                WHERE org_id = :orgId AND module_id = :moduleId
                """)
                .param("orgId", orgId)
                .param("moduleId", moduleId)
                .update();
        if (catalogLibraryIds == null) return;
        int order = 0;
        for (String libId : catalogLibraryIds) {
            if (libId == null || libId.isBlank()) continue;
            db.sql("""
                    INSERT INTO workspace_symbol_classpath
                      (org_id, module_id, catalog_library_id, sort_order)
                    VALUES (:orgId, :moduleId, :catalogLibraryId, :sortOrder)
                    """)
                    .param("orgId", orgId)
                    .param("moduleId", moduleId)
                    .param("catalogLibraryId", libId)
                    .param("sortOrder", order++)
                    .update();
        }
    }

    private List<WorkspaceConfig.SymbolClasspathEntry> loadSymbolClasspathForModule(String orgId, String moduleId) {
        return db.sql("""
                SELECT catalog_library_id FROM workspace_symbol_classpath
                WHERE org_id = :orgId AND module_id = :moduleId
                ORDER BY sort_order
                """)
                .param("orgId", orgId)
                .param("moduleId", moduleId)
                .query((rs, row) -> new WorkspaceConfig.SymbolClasspathEntry(
                        rs.getString("catalog_library_id"), null, List.of()))
                .list();
    }

    private Map<String, List<WorkspaceConfig.SymbolClasspathEntry>> loadSymbolClasspath(String orgId) {
        Map<String, List<WorkspaceConfig.SymbolClasspathEntry>> map = new LinkedHashMap<>();
        List<SymbolClasspathRow> rows = db.sql("""
                SELECT module_id, catalog_library_id FROM workspace_symbol_classpath
                WHERE org_id = :orgId
                ORDER BY module_id, sort_order
                """)
                .param("orgId", orgId)
                .query((rs, row) -> new SymbolClasspathRow(
                        rs.getString("module_id"),
                        rs.getString("catalog_library_id")))
                .list();
        for (SymbolClasspathRow row : rows) {
            map.computeIfAbsent(row.moduleId(), k -> new java.util.ArrayList<>())
                    .add(new WorkspaceConfig.SymbolClasspathEntry(row.catalogLibraryId(), null, List.of()));
        }
        return map;
    }

    private List<WorkspaceConfig.BundleIndexEntry> loadBundleIndexOrder(String orgId, String bundleName) {
        return db.sql("""
                SELECT catalog_library_id, service_module_id, repo
                FROM workspace_bundle_index_order
                WHERE org_id = :orgId AND bundle_name = :bundleName
                ORDER BY sort_order
                """)
                .param("orgId", orgId)
                .param("bundleName", bundleName)
                .query((rs, row) -> new WorkspaceConfig.BundleIndexEntry(
                        rs.getString("catalog_library_id"),
                        rs.getString("service_module_id"),
                        rs.getString("repo")))
                .list();
    }

    private void replaceBundleIndexOrder(
            String orgId, String bundleName, List<WorkspaceConfig.BundleIndexEntry> indexOrder) {
        db.sql("""
                DELETE FROM workspace_bundle_index_order
                WHERE org_id = :orgId AND bundle_name = :bundleName
                """)
                .param("orgId", orgId)
                .param("bundleName", bundleName)
                .update();
        int order = 0;
        for (WorkspaceConfig.BundleIndexEntry entry : indexOrder) {
            db.sql("""
                    INSERT INTO workspace_bundle_index_order
                      (org_id, bundle_name, sort_order, catalog_library_id, service_module_id, repo)
                    VALUES (:orgId, :bundleName, :sortOrder, :catalogLibraryId, :serviceModuleId, :repo)
                    """)
                    .param("orgId", orgId)
                    .param("bundleName", bundleName)
                    .param("sortOrder", order++)
                    .param("catalogLibraryId", entry.catalogLibrary())
                    .param("serviceModuleId", entry.serviceModule())
                    .param("repo", entry.repo())
                    .update();
        }
    }

    private OrgSettingsRow mapOrgSettings(ResultSet rs, int row) throws SQLException {
        return new OrgSettingsRow(
                rs.getString("org_id"),
                rs.getString("github_dir"),
                rs.getString("default_bundle")
        );
    }

    private WorkspaceConfig.CatalogLibraryConfig mapCatalogLibrary(ResultSet rs, int row) throws SQLException {
        return new WorkspaceConfig.CatalogLibraryConfig(
                rs.getString("library_id"),
                rs.getString("repo"),
                rs.getString("service_name"),
                readTextArray(rs.getArray("source_roots")),
                rs.getBoolean("index_ddl"),
                false
        );
    }

    private ServiceModuleBase mapServiceModuleBase(ResultSet rs, int row) throws SQLException {
        return new ServiceModuleBase(
                rs.getString("module_id"),
                rs.getString("repo"),
                readTextArray(rs.getArray("source_roots"))
        );
    }

    private BundleRow mapBundleRow(ResultSet rs, int row) throws SQLException {
        return new BundleRow(
                rs.getString("bundle_name"),
                rs.getString("trace_short_id"),
                rs.getString("trace_env")
        );
    }

    private static List<String> readTextArray(Array array) throws SQLException {
        if (array == null) return List.of();
        String[] values = (String[]) array.getArray();
        return values != null ? Arrays.asList(values) : List.of();
    }

    private static String toArrayLiteral(List<String> values) {
        if (values == null || values.isEmpty()) return "{}";
        return "{" + String.join(",", values.stream()
                .map(v -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .toList()) + "}";
    }

    record OrgSettingsRow(String orgId, String githubDir, String defaultBundle) {}

    record ServiceModuleBase(String id, String repo, List<String> sourceRoots) {}

    record BundleRow(String bundleName, String traceShortId, String traceEnv) {}

    record SymbolClasspathRow(String moduleId, String catalogLibraryId) {}

    public record CatalogLibraryUpsert(
            String id,
            String repo,
            String serviceName,
            List<String> sourceRoots,
            Boolean indexDdl
    ) {}

    public record ServiceModuleUpsert(
            String id,
            String repo,
            List<String> sourceRoots,
            List<String> catalogLibraryIds
    ) {}

    public record BundleUpsert(
            String bundleName,
            String traceShortId,
            String traceEnv,
            List<WorkspaceConfig.BundleIndexEntry> indexOrder
    ) {}
}
