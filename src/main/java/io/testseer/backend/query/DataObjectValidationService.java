package io.testseer.backend.query;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

/** Phase 5: join catalog entries against schema_object_facts for DDL confirmation. */
@Service
public class DataObjectValidationService {

    private static final Set<String> DDL_STORES = Set.of("MARIADB", "CASSANDRA");

    private final JdbcClient db;

    public DataObjectValidationService(JdbcClient db) {
        this.db = db;
    }

    public String validate(String orgId, String storeType, String physicalName, String catalogOrKeyspace) {
        if (orgId == null || storeType == null || physicalName == null || physicalName.isBlank()) {
            return null;
        }
        String normalizedStore = storeType.toUpperCase(Locale.ROOT);
        if (hasSchemaMatch(orgId, normalizedStore, physicalName, catalogOrKeyspace)) {
            return "DDL_CONFIRMED";
        }
        if (DDL_STORES.contains(normalizedStore)) {
            return "INFERRED_NOT_IN_DDL";
        }
        return null;
    }

    public boolean hasSchemaMatch(String orgId, String storeType, String physicalName, String catalogOrKeyspace) {
        if (catalogOrKeyspace != null && !catalogOrKeyspace.isBlank()) {
            return db.sql("""
                    SELECT 1
                    FROM schema_object_facts
                    WHERE org_id = :orgId
                      AND store_type = :storeType
                      AND physical_name = :physicalName
                      AND (catalog_or_keyspace IS NULL
                           OR catalog_or_keyspace = ''
                           OR catalog_or_keyspace = :catalog)
                    LIMIT 1
                    """)
                    .param("orgId", orgId)
                    .param("storeType", storeType)
                    .param("physicalName", physicalName)
                    .param("catalog", catalogOrKeyspace)
                    .query(Integer.class)
                    .optional()
                    .isPresent();
        }
        return db.sql("""
                SELECT 1
                FROM schema_object_facts
                WHERE org_id = :orgId
                  AND store_type = :storeType
                  AND physical_name = :physicalName
                LIMIT 1
                """)
                .param("orgId", orgId)
                .param("storeType", storeType)
                .param("physicalName", physicalName)
                .query(Integer.class)
                .optional()
                .isPresent();
    }
}
