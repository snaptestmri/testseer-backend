package io.testseer.backend.graph;

import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

/**
 * Minimal partner-adapter pilot graph for PA-GAP-02 / PA-GAP-03 reachability and routing tests.
 */
public final class PartnerAdapterGraphFixture {

    public static final String ORG = "quotient";
    public static final String REPO = "riq-partner-adapter-suite";
    public static final String SERVICE_NAME = "partner-adapter-suite";

    public static final String CONSUMER_FQN =
            "com.quotient.platform.partneradapter.consumer.PartnerAdapterConsumer";
    public static final String FACTORY_FQN =
            "com.quotient.platform.partneradapter.lib.factory.PartnerAdapterFactory";
    public static final String HYVEE_ADAPTER_FQN =
            "com.quotient.platform.partneradapter.lib.adapter.HyveeOfferAdapter";
    public static final String NCR_ADAPTER_FQN =
            "com.quotient.platform.partneradapter.lib.adapter.NcrBspOfferAdapter";
    public static final String DG_ADAPTER_FQN =
            "com.quotient.platform.partneradapter.lib.adapter.DGAdapter";

    private PartnerAdapterGraphFixture() {}

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
                List.of("partner-adapter-consumer/src/main/java",
                        "partner-adapter-lib/src/main/java"),
                List.of("partner-adapter-consumer/src/test/java"),
                null));
        String serviceId = svc.serviceId();

        db.sql("""
                INSERT INTO analysis_runs(job_id, org_id, service_id, commit_sha,
                    job_type, status, attempt, enqueued_at, completed_at)
                VALUES ('pa-gap-fixture', :orgId, :svcId, 'fixture', 'LOCAL', 'COMPLETE', 1, now(), now())
                """).param("orgId", ORG).param("svcId", serviceId).update();

        String consumerNode = GraphNodeIds.classNode(serviceId, CONSUMER_FQN);
        String factoryNode = GraphNodeIds.classNode(serviceId, FACTORY_FQN);
        String hyveeNode = GraphNodeIds.classNode(serviceId, HYVEE_ADAPTER_FQN);
        String ncrNode = GraphNodeIds.classNode(serviceId, NCR_ADAPTER_FQN);
        String dgNode = GraphNodeIds.classNode(serviceId, DG_ADAPTER_FQN);

        nodeRepo.upsert(GraphNode.service(serviceId, ORG, REPO, SERVICE_NAME));
        nodeRepo.upsert(GraphNode.clazz(consumerNode, ORG, REPO, SERVICE_NAME, CONSUMER_FQN));
        nodeRepo.upsert(GraphNode.clazz(factoryNode, ORG, REPO, SERVICE_NAME, FACTORY_FQN));
        nodeRepo.upsert(GraphNode.clazz(hyveeNode, ORG, REPO, SERVICE_NAME, HYVEE_ADAPTER_FQN));
        nodeRepo.upsert(GraphNode.clazz(ncrNode, ORG, REPO, SERVICE_NAME, NCR_ADAPTER_FQN));
        nodeRepo.upsert(GraphNode.clazz(dgNode, ORG, REPO, SERVICE_NAME, DG_ADAPTER_FQN));

        edgeRepo.insert(GraphEdge.invokes(consumerNode, factoryNode));
        edgeRepo.insert(GraphEdge.routesTo(factoryNode, hyveeNode));
        edgeRepo.insert(GraphEdge.routesTo(factoryNode, ncrNode));
        edgeRepo.insert(GraphEdge.routesTo(factoryNode, dgNode));

        return svc;
    }
}
