package io.testseer.backend.graph;

import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

/**
 * BL-061 receipt-service pilot graph fixture — interface API + RestController + service chain.
 */
public final class ReceiptServiceGraphFixture {

    public static final String ORG = "quotient";
    public static final String REPO = "platform-receipt-service";
    public static final String SERVICE_NAME = "receipt-service";

    public static final String API_IFACE =
            "com.quotient.platform.receiptservice.web.api.ReceiptSubmitServiceApi";
    public static final String CONTROLLER =
            "com.quotient.platform.receiptservice.web.api.ReceiptSubmitServiceApiController";
    public static final String SUBMISSION_SERVICE =
            "com.quotient.platform.receiptservice.service.ReceiptSubmissionService";
    public static final String SUBMISSION_DAO =
            "com.quotient.platform.receiptservice.dao.ReceiptSubmissionDao";
    public static final String PRODUCER =
            "com.quotient.platform.receipt.common.producer.SubmissionEventProducer";

    private ReceiptServiceGraphFixture() {}

    public static ServiceEntry load(GraphNodeRepository nodeRepo,
                                    GraphEdgeRepository edgeRepo,
                                    ServiceRegistryService svcRegistry,
                                    JdbcClient db) {
        db.sql("DELETE FROM graph_edges").update();
        db.sql("DELETE FROM graph_nodes").update();
        db.sql("DELETE FROM analysis_runs").update();
        db.sql("DELETE FROM service_registry").update();

        ServiceEntry svc = svcRegistry.register(new RegistrationRequest(
                ORG, REPO, SERVICE_NAME, "MAVEN", "service",
                List.of("receipt-service/src/main/java"),
                List.of("receipt-service/src/test/java"),
                null));
        String serviceId = svc.serviceId();

        db.sql("""
                INSERT INTO analysis_runs(job_id, org_id, service_id, commit_sha,
                    job_type, status, attempt, enqueued_at, completed_at)
                VALUES ('rs-bl061-fixture', :orgId, :svcId, 'fixture', 'LOCAL', 'COMPLETE', 1, now(), now())
                """).param("orgId", ORG).param("svcId", serviceId).update();

        String ifaceNode = GraphNodeIds.classNode(serviceId, API_IFACE);
        String controllerNode = GraphNodeIds.classNode(serviceId, CONTROLLER);
        String serviceNode = GraphNodeIds.classNode(serviceId, SUBMISSION_SERVICE);
        String daoNode = GraphNodeIds.classNode(serviceId, SUBMISSION_DAO);
        String producerNode = GraphNodeIds.classNode(serviceId, PRODUCER);

        nodeRepo.upsert(GraphNode.service(serviceId, ORG, REPO, SERVICE_NAME));
        nodeRepo.upsert(GraphNode.clazz(ifaceNode, ORG, REPO, SERVICE_NAME, API_IFACE));
        nodeRepo.upsert(GraphNode.clazz(controllerNode, ORG, REPO, SERVICE_NAME, CONTROLLER));
        nodeRepo.upsert(GraphNode.clazz(serviceNode, ORG, REPO, SERVICE_NAME, SUBMISSION_SERVICE));
        nodeRepo.upsert(GraphNode.clazz(daoNode, ORG, REPO, SERVICE_NAME, SUBMISSION_DAO));
        nodeRepo.upsert(GraphNode.clazz(producerNode, ORG, REPO, SERVICE_NAME, PRODUCER));

        edgeRepo.insert(GraphEdge.implementsEdge(ifaceNode, controllerNode));
        edgeRepo.insert(GraphEdge.invokes(controllerNode, serviceNode));
        edgeRepo.insert(GraphEdge.invokes(serviceNode, daoNode));
        edgeRepo.insert(GraphEdge.invokes(serviceNode, producerNode));
        edgeRepo.insert(GraphEdge.invokes(ifaceNode, serviceNode));

        return svc;
    }
}
