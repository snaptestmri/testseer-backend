package io.testseer.backend.query;

import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ContractEntryLinkService {

    private final JdbcClient db;
    private final ServiceRegistryService registryService;
    private final ContractOperationQueryService contractQueryService;
    private final EntryFlowService entryFlowService;

    public ContractEntryLinkService(
            JdbcClient db,
            ServiceRegistryService registryService,
            ContractOperationQueryService contractQueryService,
            EntryFlowService entryFlowService) {
        this.db = db;
        this.registryService = registryService;
        this.contractQueryService = contractQueryService;
        this.entryFlowService = entryFlowService;
    }

    public record EntryTriggerLink(
            String implementingServiceId,
            String implementingServiceName,
            String entryTriggerId,
            String triggerKind,
            String linkedHandlerFqn,
            String linkedHandlerMethod,
            String pathPattern,
            String linkStatus
    ) {}

    public record ContractOperationLinkedView(
            ContractOperationQueryService.ContractOperationView operation,
            EntryTriggerLink link
    ) {}

    public record ContractEntryFlowReport(
            ContractOperationQueryService.ContractOperationView contractOperation,
            EntryTriggerLink link,
            EntryFlowService.EntryFlowReport entryFlow
    ) {}

    public List<ContractOperationLinkedView> enrichOperations(String serviceId, String specDomain) {
        return contractQueryService.query(serviceId, specDomain).stream()
                .map(op -> new ContractOperationLinkedView(op, resolveLink(serviceId, op)))
                .toList();
    }

    public Optional<ContractEntryFlowReport> traceContractEntryFlow(
            String serviceId,
            String operationId,
            String specDomain,
            String httpMethod,
            String path,
            String env) {

        ContractOperationQueryService.ContractOperationView operation =
                findOperation(serviceId, specDomain, operationId, httpMethod, path);
        if (operation == null) {
            return Optional.empty();
        }

        EntryTriggerLink link = resolveLink(serviceId, operation);
        EntryFlowService.EntryFlowReport entryFlow = null;
        if (link != null
                && "MATCHED".equals(link.linkStatus())
                && link.implementingServiceId() != null
                && link.entryTriggerId() != null) {
            entryFlow = entryFlowService.traceEntryFlow(
                    link.implementingServiceId(), link.entryTriggerId(), null, env);
        }
        return Optional.of(new ContractEntryFlowReport(operation, link, entryFlow));
    }

    EntryTriggerLink resolveLink(
            String queryServiceId,
            ContractOperationQueryService.ContractOperationView operation) {

        String mappedServiceName = operation.mappedServiceName();
        if (mappedServiceName == null || mappedServiceName.isBlank()) {
            return new EntryTriggerLink(
                    null, null, null, null, null, null, null, "NO_MAPPED_SERVICE");
        }

        ServiceEntry querySvc = registryService.getById(queryServiceId);
        Optional<ServiceEntry> implSvc =
                registryService.getByOrgAndName(querySvc.orgId(), mappedServiceName);
        if (implSvc.isEmpty()) {
            return new EntryTriggerLink(
                    null, mappedServiceName, null, null, null, null, null, "SERVICE_NOT_REGISTERED");
        }

        String implCommit = latestEntryTriggerCommit(implSvc.get().serviceId());
        if (implCommit == null) {
            return new EntryTriggerLink(
                    implSvc.get().serviceId(), mappedServiceName,
                    null, null, null, null, null, "IMPLEMENTATION_NOT_INDEXED");
        }

        String matchKey = ContractReconciliationService.matchKey(
                operation.httpMethod(), operation.pathNormalized());
        List<ImplementationTrigger> triggers = loadImplementationTriggers(
                querySvc.orgId(), implSvc.get().serviceId(), implCommit);

        for (ImplementationTrigger trigger : triggers) {
            if (matchKey.equals(ContractReconciliationService.matchKey(
                    trigger.httpMethod(), trigger.pathNormalized()))) {
                return new EntryTriggerLink(
                        implSvc.get().serviceId(),
                        mappedServiceName,
                        trigger.triggerId(),
                        trigger.triggerKind(),
                        trigger.linkedHandlerFqn(),
                        trigger.linkedMethod(),
                        trigger.pathPattern(),
                        "MATCHED");
            }
        }

        return new EntryTriggerLink(
                implSvc.get().serviceId(), mappedServiceName,
                null, null, null, null, null, "NO_HANDLER");
    }

    private ContractOperationQueryService.ContractOperationView findOperation(
            String serviceId,
            String specDomain,
            String operationId,
            String httpMethod,
            String path) {

        List<ContractOperationQueryService.ContractOperationView> ops =
                contractQueryService.query(serviceId, specDomain);

        if (operationId != null && !operationId.isBlank()) {
            return ops.stream()
                    .filter(op -> operationId.equals(op.operationId()))
                    .findFirst()
                    .orElse(null);
        }

        if (httpMethod == null || httpMethod.isBlank() || path == null || path.isBlank()) {
            return null;
        }

        String normalized = ContractReconciliationService.normalizeInboundPath(path);
        String key = ContractReconciliationService.matchKey(httpMethod, normalized);
        return ops.stream()
                .filter(op -> key.equals(ContractReconciliationService.matchKey(
                        op.httpMethod(), op.pathNormalized())))
                .findFirst()
                .orElse(null);
    }

    private List<ImplementationTrigger> loadImplementationTriggers(
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
                    return new ImplementationTrigger(
                            rs.getString("trigger_id"),
                            rs.getString("trigger_kind"),
                            rs.getString("http_method"),
                            pathPattern,
                            ContractReconciliationService.normalizeInboundPath(pathPattern),
                            rs.getString("linked_handler_fqn"),
                            rs.getString("linked_method"));
                })
                .list();
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

    private record ImplementationTrigger(
            String triggerId,
            String triggerKind,
            String httpMethod,
            String pathPattern,
            String pathNormalized,
            String linkedHandlerFqn,
            String linkedMethod
    ) {}
}
