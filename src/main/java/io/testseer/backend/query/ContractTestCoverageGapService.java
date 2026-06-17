package io.testseer.backend.query;

import io.testseer.backend.config.ContractProperties;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class ContractTestCoverageGapService {

    private final JdbcClient db;
    private final ServiceRegistryService registryService;
    private final ContractProperties contractProperties;

    public ContractTestCoverageGapService(
            JdbcClient db,
            ServiceRegistryService registryService,
            ContractProperties contractProperties) {
        this.db = db;
        this.registryService = registryService;
        this.contractProperties = contractProperties;
    }

    public record ContractOperationSide(
            String operationId,
            String specDomain,
            String httpMethod,
            String pathTemplate,
            String pathNormalized,
            String mappedServiceName
    ) {}

    public record TestCallSide(
            String filePath,
            String sourceSymbol,
            String httpMethod,
            String path,
            String pathNormalized,
            String pathConstantRef
    ) {}

    public record ContractTestCoverageGapView(
            String gapType,
            String specDomain,
            String httpMethod,
            String pathTemplate,
            String pathNormalized,
            String operationId,
            String mappedServiceName,
            String testFilePath,
            String pathConstantRef,
            String description
    ) {}

    public List<ContractTestCoverageGapView> computeGaps(
            String serviceId,
            String testServiceId,
            String specDomain) {

        ServiceEntry implSvc = registryService.getById(serviceId);
        ServiceEntry testSvc = resolveTestService(implSvc.orgId(), testServiceId);
        if (testSvc == null) {
            return List.of();
        }

        String contractCommit = latestContractCommit(implSvc.orgId());
        String testCommit = latestTestCallCommit(testSvc.serviceId());
        if (contractCommit == null) {
            return List.of();
        }

        List<ContractOperationSide> contracts = loadContractOperations(
                implSvc.orgId(), contractCommit, implSvc.serviceName(), specDomain);
        List<TestCallSide> testCalls = testCommit == null
                ? List.of()
                : loadTestCalls(implSvc.orgId(), testSvc.serviceId(), testCommit);

        return reconcile(contracts, testCalls, implSvc.serviceName());
    }

    static List<ContractTestCoverageGapView> reconcile(
            List<ContractOperationSide> contracts,
            List<TestCallSide> testCalls,
            String mappedServiceName) {

        Map<String, ContractOperationSide> contractByKey = new LinkedHashMap<>();
        for (ContractOperationSide contract : contracts) {
            contractByKey.putIfAbsent(matchKey(contract.httpMethod(), contract.pathNormalized()), contract);
        }

        Map<String, TestCallSide> testByKey = new LinkedHashMap<>();
        for (TestCallSide testCall : testCalls) {
            testByKey.putIfAbsent(matchKey(testCall.httpMethod(), testCall.pathNormalized()), testCall);
        }

        List<ContractTestCoverageGapView> gaps = new ArrayList<>();

        for (ContractOperationSide contract : contractByKey.values()) {
            String key = matchKey(contract.httpMethod(), contract.pathNormalized());
            if (!testByKey.containsKey(key)) {
                gaps.add(new ContractTestCoverageGapView(
                        "CONTRACT_UNTESTED",
                        contract.specDomain(),
                        contract.httpMethod(),
                        contract.pathTemplate(),
                        contract.pathNormalized(),
                        contract.operationId(),
                        mappedServiceName,
                        null,
                        null,
                        "OpenAPI operation has no matching REST-Assured test HTTP call indexed"));
            }
        }

        for (TestCallSide testCall : testByKey.values()) {
            String key = matchKey(testCall.httpMethod(), testCall.pathNormalized());
            if (!contractByKey.containsKey(key)) {
                gaps.add(new ContractTestCoverageGapView(
                        "TEST_UNDOCUMENTED",
                        null,
                        testCall.httpMethod(),
                        testCall.path(),
                        testCall.pathNormalized(),
                        null,
                        mappedServiceName,
                        testCall.filePath(),
                        testCall.pathConstantRef(),
                        "REST-Assured test HTTP call has no matching OpenAPI contract operation"));
            }
        }

        return gaps;
    }

    static String matchKey(String httpMethod, String pathNormalized) {
        String method = httpMethod != null ? httpMethod.toUpperCase(Locale.ROOT) : "GET";
        String path = pathNormalized != null ? pathNormalized : "";
        return method + "|" + path;
    }

    private ServiceEntry resolveTestService(String orgId, String testServiceId) {
        if (testServiceId != null && !testServiceId.isBlank()) {
            return registryService.getById(testServiceId);
        }
        Optional<ServiceEntry> fromConfig = registryService.listAll().stream()
                .filter(s -> orgId.equals(s.orgId()))
                .filter(s -> contractProperties.isTestSuiteRepo(s.repo()))
                .findFirst();
        return fromConfig.orElse(null);
    }

    private List<ContractOperationSide> loadContractOperations(
            String orgId, String contractCommit, String mappedServiceName, String specDomain) {

        StringBuilder sql = new StringBuilder("""
                SELECT operation_id, spec_domain, http_method, path_template, path_normalized, mapped_service_name
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

        return statement.query((rs, row) -> new ContractOperationSide(
                        rs.getString("operation_id"),
                        rs.getString("spec_domain"),
                        rs.getString("http_method"),
                        rs.getString("path_template"),
                        rs.getString("path_normalized"),
                        rs.getString("mapped_service_name")))
                .list();
    }

    private List<TestCallSide> loadTestCalls(String orgId, String testServiceId, String commitSha) {
        return db.sql("""
                SELECT file_path, source_symbol, http_method, path, path_normalized, path_constant_ref
                FROM test_http_call_facts
                WHERE org_id = :orgId
                  AND service_id = :serviceId
                  AND commit_sha = :commitSha
                """)
                .param("orgId", orgId)
                .param("serviceId", testServiceId)
                .param("commitSha", commitSha)
                .query((rs, row) -> new TestCallSide(
                        rs.getString("file_path"),
                        rs.getString("source_symbol"),
                        rs.getString("http_method"),
                        rs.getString("path"),
                        rs.getString("path_normalized"),
                        rs.getString("path_constant_ref")))
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

    private String latestTestCallCommit(String testServiceId) {
        return db.sql("""
                SELECT commit_sha FROM test_http_call_facts
                WHERE service_id = :serviceId
                ORDER BY indexed_at DESC
                LIMIT 1
                """)
                .param("serviceId", testServiceId)
                .query(String.class)
                .optional()
                .orElse(null);
    }
}
