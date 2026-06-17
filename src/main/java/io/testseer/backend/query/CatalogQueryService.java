package io.testseer.backend.query;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CatalogQueryService {

    private static final String DATA_OBJECT_SELECT = """
            SELECT entity_fqn, domain_fqn, store_type, physical_name, catalog_or_keyspace,
                   collection_or_table_kind, evidence_source, confidence, attributes
            FROM data_object_facts
            """;

    private static final String SCHEMA_OBJECT_SELECT = """
            SELECT store_type, physical_name, catalog_or_keyspace, ddl_path, evidence_source
            FROM schema_object_facts
            """;

    private final JdbcClient db;

    public CatalogQueryService(JdbcClient db) {
        this.db = db;
    }

    public PageResult<DataObjectView> queryDataObjects(
            String serviceId,
            String storeType,
            String physicalName,
            int limit,
            int offset) {
        DataObjectFilter filter = new DataObjectFilter(serviceId, storeType, physicalName);
        long total = countDataObjects(filter);
        List<DataObjectView> items = fetchDataObjects(filter, limit, offset);
        return PageResult.of(items, total, limit, offset);
    }

    public PageResult<SchemaObjectView> querySchemaObjects(
            String serviceId,
            String storeType,
            String physicalName,
            int limit,
            int offset) {
        SchemaObjectFilter filter = new SchemaObjectFilter(serviceId, storeType, physicalName);
        long total = countSchemaObjects(filter);
        List<SchemaObjectView> items = fetchSchemaObjects(filter, limit, offset);
        return PageResult.of(items, total, limit, offset);
    }

    private long countDataObjects(DataObjectFilter filter) {
        var sql = new StringBuilder("SELECT COUNT(*) FROM data_object_facts");
        appendDataObjectWhere(sql, filter);
        return bindDataObjectFilter(db.sql(sql.toString()), filter)
                .query(Long.class)
                .single();
    }

    private List<DataObjectView> fetchDataObjects(DataObjectFilter filter, int limit, int offset) {
        var sql = new StringBuilder(DATA_OBJECT_SELECT);
        appendDataObjectWhere(sql, filter);
        sql.append(" ORDER BY entity_fqn LIMIT :limit OFFSET :offset");
        return bindDataObjectFilter(db.sql(sql.toString()), filter)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, row) -> new DataObjectView(
                        rs.getString("entity_fqn"),
                        rs.getString("domain_fqn"),
                        rs.getString("store_type"),
                        rs.getString("physical_name"),
                        rs.getString("catalog_or_keyspace"),
                        rs.getString("collection_or_table_kind"),
                        rs.getString("evidence_source"),
                        rs.getDouble("confidence"),
                        rs.getString("attributes")
                ))
                .list();
    }

    private long countSchemaObjects(SchemaObjectFilter filter) {
        var sql = new StringBuilder("SELECT COUNT(*) FROM schema_object_facts");
        appendSchemaObjectWhere(sql, filter);
        return bindSchemaObjectFilter(db.sql(sql.toString()), filter)
                .query(Long.class)
                .single();
    }

    private List<SchemaObjectView> fetchSchemaObjects(SchemaObjectFilter filter, int limit, int offset) {
        var sql = new StringBuilder(SCHEMA_OBJECT_SELECT);
        appendSchemaObjectWhere(sql, filter);
        sql.append(" ORDER BY store_type, physical_name LIMIT :limit OFFSET :offset");
        return bindSchemaObjectFilter(db.sql(sql.toString()), filter)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, row) -> new SchemaObjectView(
                        rs.getString("store_type"),
                        rs.getString("physical_name"),
                        rs.getString("catalog_or_keyspace"),
                        rs.getString("ddl_path"),
                        rs.getString("evidence_source")
                ))
                .list();
    }

    private static void appendDataObjectWhere(StringBuilder sql, DataObjectFilter filter) {
        sql.append(" WHERE service_id = :svcId");
        if (hasText(filter.storeType())) {
            sql.append(" AND store_type = :storeType");
        }
        if (hasText(filter.physicalName())) {
            sql.append(" AND physical_name = :physicalName");
        }
    }

    private static void appendSchemaObjectWhere(StringBuilder sql, SchemaObjectFilter filter) {
        sql.append(" WHERE service_id = :svcId");
        if (hasText(filter.storeType())) {
            sql.append(" AND store_type = :storeType");
        }
        if (hasText(filter.physicalName())) {
            sql.append(" AND physical_name = :physicalName");
        }
    }

    private static JdbcClient.StatementSpec bindDataObjectFilter(
            JdbcClient.StatementSpec query,
            DataObjectFilter filter) {
        query = query.param("svcId", filter.serviceId());
        if (hasText(filter.storeType())) {
            query = query.param("storeType", filter.storeType());
        }
        if (hasText(filter.physicalName())) {
            query = query.param("physicalName", filter.physicalName());
        }
        return query;
    }

    private static JdbcClient.StatementSpec bindSchemaObjectFilter(
            JdbcClient.StatementSpec query,
            SchemaObjectFilter filter) {
        query = query.param("svcId", filter.serviceId());
        if (hasText(filter.storeType())) {
            query = query.param("storeType", filter.storeType());
        }
        if (hasText(filter.physicalName())) {
            query = query.param("physicalName", filter.physicalName());
        }
        return query;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record DataObjectFilter(String serviceId, String storeType, String physicalName) {}

    private record SchemaObjectFilter(String serviceId, String storeType, String physicalName) {}

    public record DataObjectView(
            String entityFqn,
            String domainFqn,
            String storeType,
            String physicalName,
            String catalogOrKeyspace,
            String collectionOrTableKind,
            String evidenceSource,
            double confidence,
            String attributes
    ) {}

    public record SchemaObjectView(
            String storeType,
            String physicalName,
            String catalogOrKeyspace,
            String ddlPath,
            String evidenceSource
    ) {}
}
