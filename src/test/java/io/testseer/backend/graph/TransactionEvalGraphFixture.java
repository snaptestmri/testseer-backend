package io.testseer.backend.graph;

import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

/**
 * Minimal transaction-eval pilot graph for TE-GAP-02 / KFK-04 reachability hydration tests.
 * Mirrors consumer → {@link #EVAL_SERVICE_FQN} {@code INVOKES} edge from the suite monorepo.
 */
public final class TransactionEvalGraphFixture {

    public static final String ORG = "quotient";
    public static final String REPO = "platform-transaction-eval-consumer";
    public static final String SERVICE_NAME = "transaction-eval-suite";

    public static final String CONSUMER_FQN =
            "com.quotient.platform.transaction.eval.consumer.TransactionEvalConsumer";
    public static final String EVAL_SERVICE_FQN =
            "com.quotient.platform.transaction.eval.service.TransactionEvaluationService";
    public static final String PROCESSOR_FACTORY_FQN =
            "com.quotient.platform.transaction.eval.processors.ProcessorFactory";
    public static final String DEFAULT_PROCESSOR_FQN =
            "com.quotient.platform.transaction.eval.processors.DefaultTxnEvalProcessor";

    private TransactionEvalGraphFixture() {}

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
                List.of("transaction-eval-consumer/src/main/java"),
                List.of("transaction-eval-consumer/src/test/java"),
                null));
        String serviceId = svc.serviceId();

        db.sql("""
                INSERT INTO analysis_runs(job_id, org_id, service_id, commit_sha,
                    job_type, status, attempt, enqueued_at, completed_at)
                VALUES ('te-gap-02-fixture', :orgId, :svcId, 'fixture', 'LOCAL', 'COMPLETE', 1, now(), now())
                """).param("orgId", ORG).param("svcId", serviceId).update();

        String consumerNode = GraphNodeIds.classNode(serviceId, CONSUMER_FQN);
        String evalServiceNode = GraphNodeIds.classNode(serviceId, EVAL_SERVICE_FQN);
        String factoryNode = GraphNodeIds.classNode(serviceId, PROCESSOR_FACTORY_FQN);
        String processorNode = GraphNodeIds.classNode(serviceId, DEFAULT_PROCESSOR_FQN);

        nodeRepo.upsert(GraphNode.service(serviceId, ORG, REPO, SERVICE_NAME));
        nodeRepo.upsert(GraphNode.clazz(consumerNode, ORG, REPO, SERVICE_NAME, CONSUMER_FQN));
        nodeRepo.upsert(GraphNode.clazz(evalServiceNode, ORG, REPO, SERVICE_NAME, EVAL_SERVICE_FQN));
        nodeRepo.upsert(GraphNode.clazz(factoryNode, ORG, REPO, SERVICE_NAME, PROCESSOR_FACTORY_FQN));
        nodeRepo.upsert(GraphNode.clazz(processorNode, ORG, REPO, SERVICE_NAME, DEFAULT_PROCESSOR_FQN));

        edgeRepo.insert(GraphEdge.invokes(consumerNode, evalServiceNode));
        edgeRepo.insert(GraphEdge.invokes(evalServiceNode, factoryNode));
        edgeRepo.insert(GraphEdge.routesTo(factoryNode, processorNode));

        return svc;
    }
}
