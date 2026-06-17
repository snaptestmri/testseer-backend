package io.testseer.backend.query;

import io.testseer.backend.ingestion.contract.OpenApiSpecParser;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ContractReconciliationService {

    private static final List<String> INBOUND_TRIGGER_KINDS =
            List.of("REST_INBOUND", "WEBHOOK_INBOUND");

    private final JdbcClient db;
    private final ServiceRegistryService registryService;

    public ContractReconciliationService(JdbcClient db, ServiceRegistryService registryService) {
        this.db = db;
        this.registryService = registryService;
    }

    public record ContractSide(
            String operationId,
            String operationIdOpenapi,
            String specDomain,
            String httpMethod,
            String pathTemplate,
            String pathNormalized,
            String mappedServiceName
    ) {}

    public record ImplementationSide(
            String triggerId,
            String triggerKind,
            String httpMethod,
            String pathPattern,
            String pathNormalized,
            String linkedHandlerFqn,
            String linkedMethod
    ) {}

    public record ContractGapView(
            String gapType,
            String specDomain,
            String httpMethod,
            String pathTemplate,
            String pathNormalized,
            String operationId,
            String operationIdOpenapi,
            String linkedHandlerFqn,
            String linkedMethod,
            String mappedServiceName,
            String description
    ) {}

    public List<ContractGapView> computeGaps(String serviceId, String specDomain) {
        ServiceEntry svc = registryService.getById(serviceId);
        if (isApisCatalogService(svc)) {
            return computeGapsForCatalogLibrary(svc, specDomain);
        }
        return reconcileForImplementingService(svc.orgId(), svc.serviceId(), svc.serviceName(), specDomain);
    }

    private List<ContractGapView> computeGapsForCatalogLibrary(ServiceEntry catalogSvc, String specDomain) {
        String orgId = catalogSvc.orgId();
        String contractCommit = latestContractCommit(orgId);
        if (contractCommit == null) {
            return List.of();
        }

        List<String> mappedServices = loadMappedServiceNames(
                orgId, contractCommit, catalogSvc.serviceId(), specDomain);
        List<ContractGapView> gaps = new ArrayList<>();
        for (String mappedServiceName : mappedServices) {
            registryService.getByOrgAndName(orgId, mappedServiceName).ifPresent(implSvc ->
                    gaps.addAll(reconcileForImplementingService(
                            orgId, implSvc.serviceId(), mappedServiceName, specDomain)));
        }
        return gaps;
    }

    private List<ContractGapView> reconcileForImplementingService(
            String orgId,
            String implementingServiceId,
            String mappedServiceName,
            String specDomain) {

        String contractCommit = latestContractCommit(orgId);
        String implCommit = latestEntryTriggerCommit(implementingServiceId);
        if (contractCommit == null) {
            return List.of();
        }

        List<ContractSide> contracts = loadContractOperations(
                orgId, contractCommit, mappedServiceName, specDomain);
        List<ImplementationSide> implementations = implCommit == null
                ? List.of()
                : loadImplementationEndpoints(orgId, implementingServiceId, implCommit);

        return reconcile(contracts, implementations, mappedServiceName);
    }

    static List<ContractGapView> reconcile(
            List<ContractSide> contracts,
            List<ImplementationSide> implementations,
            String mappedServiceName) {

        Map<String, ContractSide> contractByKey = new LinkedHashMap<>();
        for (ContractSide contract : contracts) {
            contractByKey.putIfAbsent(matchKey(contract.httpMethod(), contract.pathNormalized()), contract);
        }

        Map<String, ImplementationSide> implByKey = new LinkedHashMap<>();
        for (ImplementationSide impl : implementations) {
            implByKey.putIfAbsent(matchKey(impl.httpMethod(), impl.pathNormalized()), impl);
        }

        List<ContractGapView> gaps = new ArrayList<>();

        for (ContractSide contract : contractByKey.values()) {
            String key = matchKey(contract.httpMethod(), contract.pathNormalized());
            if (!implByKey.containsKey(key)) {
                gaps.add(new ContractGapView(
                        "CONTRACT_ONLY",
                        contract.specDomain(),
                        contract.httpMethod(),
                        contract.pathTemplate(),
                        contract.pathNormalized(),
                        contract.operationId(),
                        contract.operationIdOpenapi(),
                        null,
                        null,
                        mappedServiceName,
                        "OpenAPI operation documented but no matching inbound REST handler indexed"));
            }
        }

        for (ImplementationSide impl : implByKey.values()) {
            String key = matchKey(impl.httpMethod(), impl.pathNormalized());
            if (!contractByKey.containsKey(key)) {
                gaps.add(new ContractGapView(
                        "IMPLEMENTATION_ONLY",
                        null,
                        impl.httpMethod(),
                        impl.pathPattern(),
                        impl.pathNormalized(),
                        null,
                        null,
                        impl.linkedHandlerFqn(),
                        impl.linkedMethod(),
                        mappedServiceName,
                        "Inbound REST handler indexed but no matching OpenAPI contract operation"));
            }
        }

        return gaps;
    }

    static String matchKey(String httpMethod, String pathNormalized) {
        String method = httpMethod != null ? httpMethod.toUpperCase(Locale.ROOT) : "GET";
        String path = pathNormalized != null ? pathNormalized : "";
        return method + "|" + path;
    }

    static String normalizeInboundPath(String path) {
        if (path == null || path.isBlank()) {
            return OpenApiSpecParser.normalizePath("/");
        }
        String withSlash = path.startsWith("/") ? path : "/" + path;
        return OpenApiSpecParser.normalizePath(withSlash);
    }

    private List<String> loadMappedServiceNames(
            String orgId, String contractCommit, String catalogServiceId, String specDomain) {

        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT mapped_service_name
                FROM contract_operation_facts
                WHERE org_id = :orgId
                  AND commit_sha = :commitSha
                  AND service_id = :catalogServiceId
                  AND mapped_service_name IS NOT NULL
                """);
        if (specDomain != null && !specDomain.isBlank()) {
            sql.append(" AND spec_domain = :specDomain");
        }
        sql.append(" ORDER BY mapped_service_name");

        var statement = db.sql(sql.toString())
                .param("orgId", orgId)
                .param("commitSha", contractCommit)
                .param("catalogServiceId", catalogServiceId);
        if (specDomain != null && !specDomain.isBlank()) {
            statement = statement.param("specDomain", specDomain);
        }
        return statement.query(String.class).list();
    }

    private List<ContractSide> loadContractOperations(
            String orgId, String contractCommit, String mappedServiceName, String specDomain) {

        StringBuilder sql = new StringBuilder("""
                SELECT operation_id, operation_id_openapi, spec_domain, http_method,
                       path_template, path_normalized, mapped_service_name
                FROM contract_operation_facts
                WHERE org_id = :orgId
                  AND commit_sha = :commitSha
                  AND mapped_service_name = :mappedServiceName
                """);
        if (specDomain != null && !specDomain.isBlank()) {
            sql.append(" AND spec_domain = :specDomain");
        }

        var statement = db.sql(sql.toString())
                .param("orgId", orgId)
                .param("commitSha", contractCommit)
                .param("mappedServiceName", mappedServiceName);
        if (specDomain != null && !specDomain.isBlank()) {
            statement = statement.param("specDomain", specDomain);
        }

        return statement.query((rs, row) -> new ContractSide(
                        rs.getString("operation_id"),
                        rs.getString("operation_id_openapi"),
                        rs.getString("spec_domain"),
                        rs.getString("http_method"),
                        rs.getString("path_template"),
                        rs.getString("path_normalized"),
                        rs.getString("mapped_service_name")))
                .list();
    }

    private List<ImplementationSide> loadImplementationEndpoints(
            String orgId, String serviceId, String commitSha) {

        return db.sql("""
                SELECT trigger_id, trigger_kind, http_method, path_pattern,
                       linked_handler_fqn, linked_method
                FROM entry_trigger_facts
                WHERE org_id = :orgId
                  AND service_id = :serviceId
                  AND commit_sha = :commitSha
                  AND trigger_kind IN ('REST_INBOUND', 'WEBHOOK_INBOUND')
                  AND http_method IS NOT NULL
                  AND path_pattern IS NOT NULL
                """)
                .param("orgId", orgId)
                .param("serviceId", serviceId)
                .param("commitSha", commitSha)
                .query((rs, row) -> {
                    String pathPattern = rs.getString("path_pattern");
                    return new ImplementationSide(
                            rs.getString("trigger_id"),
                            rs.getString("trigger_kind"),
                            rs.getString("http_method"),
                            pathPattern,
                            normalizeInboundPath(pathPattern),
                            rs.getString("linked_handler_fqn"),
                            rs.getString("linked_method"));
                })
                .list();
    }

    private String latestContractCommit(String orgId) {
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

    private String latestEntryTriggerCommit(String serviceId) {
        return db.sql("""
                SELECT commit_sha FROM entry_trigger_facts
                WHERE service_id = :serviceId
                ORDER BY indexed_at DESC
                LIMIT 1
                """)
                .param("serviceId", serviceId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private static boolean isApisCatalogService(ServiceEntry svc) {
        return "library".equalsIgnoreCase(svc.moduleType())
                && svc.repo() != null
                && svc.repo().contains("apis-optimus");
    }
}
