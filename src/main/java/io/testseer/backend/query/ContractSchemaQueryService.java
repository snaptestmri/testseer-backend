package io.testseer.backend.query;

import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ContractSchemaQueryService {

    private final JdbcClient db;
    private final ServiceRegistryService registryService;

    public ContractSchemaQueryService(JdbcClient db, ServiceRegistryService registryService) {
        this.db = db;
        this.registryService = registryService;
    }

    public record ContractSchemaView(
            String schemaId,
            String schemaTitle,
            String schemaType,
            String topLevelFieldsJson,
            String requiredFieldsJson,
            String nestedFieldPathsJson,
            String specFile,
            String evidenceSource
    ) {}

    public List<ContractSchemaView> query(String serviceId, String schemaId) {
        ServiceEntry svc = registryService.getById(serviceId);
        String latestCommit = latestSchemaCommit(svc.orgId(), serviceId);
        if (latestCommit == null) {
            return List.of();
        }

        StringBuilder sql = new StringBuilder("""
                SELECT schema_id, schema_title, schema_type,
                       top_level_fields::text AS top_level_fields,
                       required_fields::text AS required_fields,
                       nested_field_paths::text AS nested_field_paths,
                       spec_file, evidence_source
                FROM contract_schema_facts
                WHERE org_id = :orgId
                  AND service_id = :serviceId
                  AND commit_sha = :commitSha
                """);
        if (schemaId != null && !schemaId.isBlank()) {
            sql.append(" AND schema_id = :schemaId");
        }
        sql.append(" ORDER BY schema_id");

        var statement = db.sql(sql.toString())
                .param("orgId", svc.orgId())
                .param("serviceId", serviceId)
                .param("commitSha", latestCommit);
        if (schemaId != null && !schemaId.isBlank()) {
            statement = statement.param("schemaId", schemaId);
        }

        return statement.query((rs, row) -> new ContractSchemaView(
                        rs.getString("schema_id"),
                        rs.getString("schema_title"),
                        rs.getString("schema_type"),
                        rs.getString("top_level_fields"),
                        rs.getString("required_fields"),
                        rs.getString("nested_field_paths"),
                        rs.getString("spec_file"),
                        rs.getString("evidence_source")))
                .list();
    }

    private String latestSchemaCommit(String orgId, String serviceId) {
        return db.sql("""
                SELECT commit_sha FROM contract_schema_facts
                WHERE org_id = :orgId AND service_id = :serviceId
                ORDER BY indexed_at DESC
                LIMIT 1
                """)
                .param("orgId", orgId)
                .param("serviceId", serviceId)
                .query(String.class)
                .optional()
                .orElse(null);
    }
}
