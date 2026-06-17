package io.testseer.backend.admin;

import io.testseer.backend.graph.GraphEdgeRepository;
import io.testseer.backend.graph.GraphNodeRepository;
import io.testseer.backend.query.CacheService;
import io.testseer.backend.query.IndexCompleteNotifier;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class IndexClearService {

    private static final Logger log = LoggerFactory.getLogger(IndexClearService.class);

    private final JdbcClient db;
    private final MongoTemplate mongo;
    private final ServiceRegistryService registryService;
    private final GraphNodeRepository nodeRepo;
    private final GraphEdgeRepository edgeRepo;
    private final CacheService cacheService;
    private final IndexCompleteNotifier indexCompleteNotifier;

    public IndexClearService(JdbcClient db,
                             MongoTemplate mongo,
                             ServiceRegistryService registryService,
                             GraphNodeRepository nodeRepo,
                             GraphEdgeRepository edgeRepo,
                             CacheService cacheService,
                             IndexCompleteNotifier indexCompleteNotifier) {
        this.db = db;
        this.mongo = mongo;
        this.registryService = registryService;
        this.nodeRepo = nodeRepo;
        this.edgeRepo = edgeRepo;
        this.cacheService = cacheService;
        this.indexCompleteNotifier = indexCompleteNotifier;
    }

    /**
     * Clear indexed facts for one service, entire org, or messaging facts only.
     *
     * @param scope SERVICE | ORG | MESSAGING
     */
    @Transactional
    public IndexClearResponse clear(IndexClearRequest request) {
        return switch (request.scope().toUpperCase()) {
            case "SERVICE" -> clearService(requireServiceId(request));
            case "ORG" -> clearOrg(requireOrgId(request), request.includeRegistry());
            case "MESSAGING" -> clearMessaging(requireServiceId(request));
            default -> throw new IllegalArgumentException(
                    "scope must be SERVICE, ORG, or MESSAGING — got: " + request.scope());
        };
    }

    public IndexClearResponse clearService(String serviceId) {
        ServiceEntry svc = registryService.getById(serviceId);
        Map<String, Integer> deleted = new LinkedHashMap<>();

        deleted.put("graphEdges", deleteGraphForService(svc));
        deleted.put("symbolFacts", deleteFrom("symbol_facts", "service_id", serviceId));
        deleted.put("outboundCallFacts", deleteFrom("outbound_call_facts", "service_id", serviceId));
        deleted.put("peripheralFacts", deleteFrom("peripheral_facts", "service_id", serviceId));
        deleted.put("unsupportedConstructFacts", deleteFrom("unsupported_construct_facts", "service_id", serviceId));
        deleted.put("pubsubResourceFacts", deleteFrom("pubsub_resource_facts", "service_id", serviceId));
        deleted.put("messageSchemaFacts", deleteFrom("message_schema_facts", "service_id", serviceId));
        deleted.put("dataAccessFacts", deleteFrom("data_access_facts", "service_id", serviceId));
        deleted.put("flowGateFacts", deleteFrom("flow_gate_facts", "service_id", serviceId));
        deleted.put("validationHintFacts", deleteFrom("validation_hint_facts", "service_id", serviceId));
        deleted.put("externalEndpointFacts", deleteFrom("external_endpoint_facts", "service_id", serviceId));
        deleted.put("externalCallSiteFacts", deleteFrom("external_call_site_facts", "service_id", serviceId));
        deleted.put("entryTriggerFacts", deleteFrom("entry_trigger_facts", "service_id", serviceId));
        deleted.put("dataObjectFacts", deleteFrom("data_object_facts", "service_id", serviceId));
        deleted.put("accessorMethodFacts", deleteFrom("accessor_method_facts", "service_id", serviceId));
        deleted.put("schemaObjectFacts", deleteFrom("schema_object_facts", "service_id", serviceId));
        deleted.put("consistencyScenarioFacts", deleteFrom("consistency_scenario_facts", "service_id", serviceId));
        deleted.put("contractOperationFacts", deleteFrom("contract_operation_facts", "service_id", serviceId));
        deleted.put("contractSchemaFacts", deleteFrom("contract_schema_facts", "service_id", serviceId));
        deleted.put("testHttpCallFacts", deleteFrom("test_http_call_facts", "service_id", serviceId));
        deleted.put("asyncRetryPathFacts", deleteFrom("async_retry_path_facts", "service_id", serviceId));
        deleted.put("mavenModuleFacts", deleteFrom("maven_module_facts", "service_id", serviceId));
        deleted.put("mavenDependencyFacts", deleteFrom("maven_dependency_facts", "service_id", serviceId));
        deleted.put("analysisRuns", deleteFrom("analysis_runs", "service_id", serviceId));
        deleted.put("parsedModels", deleteMongoModels(svc.orgId(), svc.repo(), serviceId));

        cacheService.invalidate(svc.orgId(), svc.repo(), serviceId);
        indexCompleteNotifier.notifyCleared(svc.orgId(), svc.repo(), serviceId);
        log.info("Cleared index for service {} ({})", svc.serviceName(), serviceId);

        return new IndexClearResponse("SERVICE", svc.orgId(), serviceId, svc.serviceName(), deleted);
    }

    public IndexClearResponse clearMessaging(String serviceId) {
        ServiceEntry svc = registryService.getById(serviceId);
        Map<String, Integer> deleted = new LinkedHashMap<>();

        deleted.put("pubsubResourceFacts", deleteFrom("pubsub_resource_facts", "service_id", serviceId));
        deleted.put("messageSchemaFacts", deleteFrom("message_schema_facts", "service_id", serviceId));
        deleted.put("dataAccessFacts", deleteFrom("data_access_facts", "service_id", serviceId));
        deleted.put("flowGateFacts", deleteFrom("flow_gate_facts", "service_id", serviceId));
        deleted.put("validationHintFacts", deleteFrom("validation_hint_facts", "service_id", serviceId));

        cacheService.invalidate(svc.orgId(), svc.repo(), serviceId);
        indexCompleteNotifier.notifyCleared(svc.orgId(), svc.repo(), serviceId);
        log.info("Cleared messaging facts for service {} ({})", svc.serviceName(), serviceId);

        return new IndexClearResponse("MESSAGING", svc.orgId(), serviceId, svc.serviceName(), deleted);
    }

    public IndexClearResponse clearOrg(String orgId, boolean includeRegistry) {
        Map<String, Integer> deleted = new LinkedHashMap<>();

        deleted.put("graphEdges", db.sql("""
                DELETE FROM graph_edges
                WHERE from_node IN (SELECT id FROM graph_nodes WHERE org_id = :orgId)
                   OR to_node IN (SELECT id FROM graph_nodes WHERE org_id = :orgId)
                """).param("orgId", orgId).update());
        deleted.put("graphNodes", db.sql("DELETE FROM graph_nodes WHERE org_id = :orgId")
                .param("orgId", orgId).update());
        deleted.put("symbolFacts", deleteFromOrg("symbol_facts", orgId));
        deleted.put("outboundCallFacts", deleteFromOrg("outbound_call_facts", orgId));
        deleted.put("peripheralFacts", deleteFromOrg("peripheral_facts", orgId));
        deleted.put("unsupportedConstructFacts", deleteFromOrg("unsupported_construct_facts", orgId));
        deleted.put("pubsubResourceFacts", deleteFromOrg("pubsub_resource_facts", orgId));
        deleted.put("messageSchemaFacts", deleteFromOrg("message_schema_facts", orgId));
        deleted.put("dataAccessFacts", deleteFromOrg("data_access_facts", orgId));
        deleted.put("flowGateFacts", deleteFromOrg("flow_gate_facts", orgId));
        deleted.put("validationHintFacts", deleteFromOrg("validation_hint_facts", orgId));
        deleted.put("externalEndpointFacts", deleteFromOrg("external_endpoint_facts", orgId));
        deleted.put("externalCallSiteFacts", deleteFromOrg("external_call_site_facts", orgId));
        deleted.put("entryTriggerFacts", deleteFromOrg("entry_trigger_facts", orgId));
        deleted.put("dataObjectFacts", deleteFromOrg("data_object_facts", orgId));
        deleted.put("accessorMethodFacts", deleteFromOrg("accessor_method_facts", orgId));
        deleted.put("schemaObjectFacts", deleteFromOrg("schema_object_facts", orgId));
        deleted.put("consistencyScenarioFacts", deleteFromOrg("consistency_scenario_facts", orgId));
        deleted.put("contractOperationFacts", deleteFromOrg("contract_operation_facts", orgId));
        deleted.put("contractSchemaFacts", deleteFromOrg("contract_schema_facts", orgId));
        deleted.put("testHttpCallFacts", deleteFromOrg("test_http_call_facts", orgId));
        deleted.put("asyncRetryPathFacts", deleteFromOrg("async_retry_path_facts", orgId));
        deleted.put("mavenModuleFacts", deleteFromOrg("maven_module_facts", orgId));
        deleted.put("mavenDependencyFacts", deleteFromOrg("maven_dependency_facts", orgId));
        deleted.put("analysisRuns", deleteFromOrg("analysis_runs", orgId));
        deleted.put("parsedModels", deleteMongoModelsForOrg(orgId));

        if (includeRegistry) {
            deleted.put("serviceRegistry", db.sql("DELETE FROM service_registry WHERE org_id = :orgId")
                    .param("orgId", orgId).update());
        }

        log.info("Cleared org {} (includeRegistry={})", orgId, includeRegistry);
        return new IndexClearResponse("ORG", orgId, null, null, deleted);
    }

    private int deleteGraphForService(ServiceEntry svc) {
        String serviceId = svc.serviceId();
        String serviceName = svc.serviceName();
        String classPrefix = serviceId + "::%";

        int edges = db.sql("""
                DELETE FROM graph_edges
                WHERE from_node IN (
                    SELECT id FROM graph_nodes
                    WHERE org_id = :orgId
                      AND (service = :serviceId OR service = :serviceName OR id LIKE :classPrefix)
                )
                   OR to_node IN (
                    SELECT id FROM graph_nodes
                    WHERE org_id = :orgId
                      AND (service = :serviceId OR service = :serviceName OR id LIKE :classPrefix)
                )
                """)
                .param("orgId", svc.orgId())
                .param("serviceId", serviceId)
                .param("serviceName", serviceName)
                .param("classPrefix", classPrefix)
                .update();

        nodeRepo.deleteByServiceIdOrName(svc.orgId(), serviceId, serviceName, classPrefix);
        return edges;
    }

    private int deleteFrom(String table, String column, String value) {
        return db.sql("DELETE FROM " + table + " WHERE " + column + " = :val")
                .param("val", value)
                .update();
    }

    private int deleteFromOrg(String table, String orgId) {
        return db.sql("DELETE FROM " + table + " WHERE org_id = :orgId")
                .param("orgId", orgId)
                .update();
    }

    private int deleteMongoModels(String orgId, String repo, String serviceId) {
        Query q = new Query(Criteria.where("orgId").is(orgId)
                .and("repo").is(repo)
                .and("serviceId").is(serviceId));
        return (int) mongo.remove(q, "parsed_models").getDeletedCount();
    }

    private int deleteMongoModelsForOrg(String orgId) {
        Query q = new Query(Criteria.where("orgId").is(orgId));
        return (int) mongo.remove(q, "parsed_models").getDeletedCount();
    }

    private String requireServiceId(IndexClearRequest request) {
        if (request.serviceId() == null || request.serviceId().isBlank()) {
            throw new IllegalArgumentException("serviceId is required when scope=SERVICE or MESSAGING");
        }
        registryService.getById(request.serviceId());
        return request.serviceId();
    }

    private String requireOrgId(IndexClearRequest request) {
        if (request.orgId() == null || request.orgId().isBlank()) {
            throw new IllegalArgumentException("orgId is required when scope=ORG");
        }
        return request.orgId();
    }
}
