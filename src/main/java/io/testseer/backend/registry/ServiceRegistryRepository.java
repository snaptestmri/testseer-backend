package io.testseer.backend.registry;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
public class ServiceRegistryRepository {

    private final JdbcClient db;

    public ServiceRegistryRepository(JdbcClient db) {
        this.db = db;
    }

    public void save(ServiceEntry e) {
        db.sql("""
                INSERT INTO service_registry
                  (service_id, org_id, repo, service_name, module_type, build_tool,
                   source_roots, test_roots, owner_team, enabled)
                VALUES (:serviceId, :orgId, :repo, :serviceName, :moduleType, :buildTool,
                        :sourceRoots::text[], :testRoots::text[], :ownerTeam, :enabled)
                ON CONFLICT (service_id) DO UPDATE SET
                  enabled      = EXCLUDED.enabled,
                  source_roots = EXCLUDED.source_roots,
                  test_roots   = EXCLUDED.test_roots,
                  owner_team   = EXCLUDED.owner_team,
                  updated_at   = now()
                """)
                .param("serviceId",   e.serviceId())
                .param("orgId",       e.orgId())
                .param("repo",        e.repo())
                .param("serviceName", e.serviceName())
                .param("moduleType",  e.moduleType() != null ? e.moduleType() : "service")
                .param("buildTool",   e.buildTool())
                .param("sourceRoots", toArrayLiteral(e.sourceRoots()))
                .param("testRoots",   toArrayLiteral(e.testRoots()))
                .param("ownerTeam",   e.ownerTeam())
                .param("enabled",     e.enabled())
                .update();
    }

    public Optional<ServiceEntry> findById(String serviceId) {
        return db.sql("SELECT * FROM service_registry WHERE service_id = :id")
                .param("id", serviceId)
                .query(this::mapRow)
                .optional();
    }

    public Optional<ServiceEntry> findByOrgRepoService(String orgId, String repo, String serviceName) {
        return db.sql("""
                SELECT * FROM service_registry
                WHERE org_id = :orgId AND repo = :repo AND service_name = :serviceName
                """)
                .param("orgId",       orgId)
                .param("repo",        repo)
                .param("serviceName", serviceName)
                .query(this::mapRow)
                .optional();
    }

    public List<ServiceEntry> findAll() {
        return db.sql("SELECT * FROM service_registry ORDER BY org_id, repo, service_name")
                .query(this::mapRow)
                .list();
    }

    public int disable(String serviceId) {
        return db.sql("UPDATE service_registry SET enabled = false, updated_at = now() WHERE service_id = :id")
                .param("id", serviceId)
                .update();
    }

    public int updateFields(String serviceId, RegistryUpdateRequest req) {
        return db.sql("""
                UPDATE service_registry SET
                  enabled      = COALESCE(:enabled, enabled),
                  source_roots = COALESCE(:sourceRoots::text[], source_roots),
                  test_roots   = COALESCE(:testRoots::text[], test_roots),
                  owner_team   = COALESCE(:ownerTeam, owner_team),
                  updated_at   = now()
                WHERE service_id = :id
                """)
                .param("enabled",     req.enabled())
                .param("sourceRoots", req.sourceRoots() != null ? toArrayLiteral(req.sourceRoots()) : null)
                .param("testRoots",   req.testRoots() != null ? toArrayLiteral(req.testRoots()) : null)
                .param("ownerTeam",   req.ownerTeam())
                .param("id",          serviceId)
                .update();
    }

    private ServiceEntry mapRow(ResultSet rs, int row) throws SQLException {
        return new ServiceEntry(
                rs.getString("service_id"),
                rs.getString("org_id"),
                rs.getString("repo"),
                rs.getString("service_name"),
                rs.getString("module_type"),
                rs.getString("build_tool"),
                arrayToList(rs.getArray("source_roots")),
                arrayToList(rs.getArray("test_roots")),
                rs.getString("owner_team"),
                rs.getBoolean("enabled"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private static String toArrayLiteral(List<String> items) {
        if (items == null || items.isEmpty()) return "{src/main/java}";
        return "{" + String.join(",", items) + "}";
    }

    private static List<String> arrayToList(Array arr) throws SQLException {
        if (arr == null) return List.of();
        return Arrays.asList((String[]) arr.getArray());
    }
}
