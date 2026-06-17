package io.testseer.backend.query;

import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ContractOperationQueryService {

    private final JdbcClient db;
    private final ServiceRegistryService registryService;

    public ContractOperationQueryService(JdbcClient db, ServiceRegistryService registryService) {
        this.db = db;
        this.registryService = registryService;
    }

    public record ContractOperationView(
            String operationId,
            String specDomain,
            String specFile,
            String openapiVersion,
            String operationIdOpenapi,
            String httpMethod,
            String pathTemplate,
            String pathNormalized,
            String summary,
            String tagsJson,
            String requestSchemaRef,
            String responseSchemaRef,
            String requestFieldSummaryJson,
            String responseFieldSummaryJson,
            String serverUrlsJson,
            String mappedServiceName,
            String evidenceSource,
            double confidence
    ) {}

    public List<ContractOperationView> query(String serviceId, String specDomain) {
        ServiceEntry svc = registryService.getById(serviceId);
        String latestCommit = latestCommitForOrg(svc.orgId());
        if (latestCommit == null) {
            return List.of();
        }

        StringBuilder sql = new StringBuilder("""
                SELECT operation_id, spec_domain, spec_file, openapi_version, operation_id_openapi,
                       http_method, path_template, path_normalized, summary,
                       tags::text AS tags, request_schema_ref, response_schema_ref,
                       request_field_summary::text AS request_field_summary,
                       response_field_summary::text AS response_field_summary,
                       server_urls::text AS server_urls, mapped_service_name,
                       evidence_source, confidence
                FROM contract_operation_facts
                WHERE org_id = :orgId
                  AND commit_sha = :commitSha
                """);

        if (isApisCatalogService(svc)) {
            sql.append(" AND service_id = :scopeServiceId");
        } else {
            sql.append(" AND mapped_service_name = :scopeServiceName");
        }
        if (specDomain != null && !specDomain.isBlank()) {
            sql.append(" AND spec_domain = :specDomain");
        }
        sql.append(" ORDER BY spec_domain, path_template, http_method");

        var statement = db.sql(sql.toString())
                .param("orgId", svc.orgId())
                .param("commitSha", latestCommit);
        if (isApisCatalogService(svc)) {
            statement = statement.param("scopeServiceId", serviceId);
        } else {
            statement = statement.param("scopeServiceName", svc.serviceName());
        }
        if (specDomain != null && !specDomain.isBlank()) {
            statement = statement.param("specDomain", specDomain);
        }

        return statement.query((rs, row) -> new ContractOperationView(
                        rs.getString("operation_id"),
                        rs.getString("spec_domain"),
                        rs.getString("spec_file"),
                        rs.getString("openapi_version"),
                        rs.getString("operation_id_openapi"),
                        rs.getString("http_method"),
                        rs.getString("path_template"),
                        rs.getString("path_normalized"),
                        rs.getString("summary"),
                        rs.getString("tags"),
                        rs.getString("request_schema_ref"),
                        rs.getString("response_schema_ref"),
                        rs.getString("request_field_summary"),
                        rs.getString("response_field_summary"),
                        rs.getString("server_urls"),
                        rs.getString("mapped_service_name"),
                        rs.getString("evidence_source"),
                        rs.getDouble("confidence")
                ))
                .list();
    }

    public String catalogServiceIdForFreshness(String orgId) {
        return db.sql("""
                SELECT service_id FROM contract_operation_facts
                WHERE org_id = :orgId
                ORDER BY indexed_at DESC
                LIMIT 1
                """)
                .param("orgId", orgId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private String latestCommitForOrg(String orgId) {
        return db.sql("""
                SELECT commit_sha FROM contract_operation_facts
                WHERE org_id = :orgId
                ORDER BY indexed_at DESC
                LIMIT 1
                """)
                .param("orgId", orgId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private static boolean isApisCatalogService(ServiceEntry svc) {
        return svc.repo() != null && svc.repo().contains("apis-optimus");
    }
}
