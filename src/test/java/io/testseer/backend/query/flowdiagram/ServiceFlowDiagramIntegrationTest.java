package io.testseer.backend.query.flowdiagram;

import io.testseer.backend.graph.GraphEdge;
import io.testseer.backend.graph.GraphEdgeRepository;
import io.testseer.backend.graph.GraphNode;
import io.testseer.backend.graph.GraphNodeIds;
import io.testseer.backend.graph.GraphNodeRepository;
import io.testseer.backend.registry.RegistrationRequest;
import io.testseer.backend.registry.ServiceRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration," +
        "com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubAutoConfiguration,com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubReactiveAutoConfiguration"
})
@Testcontainers
@Import(io.testseer.backend.KafkaTestConfiguration.class)
class ServiceFlowDiagramIntegrationTest {

  private static final String ORG = "quotient";
  private static final String REPO = "transaction-eval-suite";
  private static final String COMMIT = "deadbeef";
  private String svcId;
  private static final String PKG = "com.quotient.platform.transaction.eval";
  private static final String CONSUMER = PKG + ".consumer.TransactionEvalConsumer";
  private static final String EVAL_SVC = PKG + ".service.TransactionEvaluationService";
  private static final String FACTORY = PKG + ".processors.ProcessorFactory";
  private static final String DEFAULT_PROC = PKG + ".processors.DefaultTxnEvalProcessor";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

  @Autowired ServiceFlowDiagramComposer composer;
  @Autowired GraphNodeRepository nodeRepo;
  @Autowired GraphEdgeRepository edgeRepo;
  @Autowired ServiceRegistryService svcRegistry;
  @Autowired JdbcClient db;

  @BeforeEach
  void setup() {
    db.sql("DELETE FROM pubsub_resource_facts").update();
    db.sql("DELETE FROM entry_trigger_facts").update();
    db.sql("DELETE FROM routing_table_facts").update();
    db.sql("DELETE FROM graph_edges").update();
    db.sql("DELETE FROM graph_nodes").update();
    db.sql("DELETE FROM service_registry").update();

    svcRegistry.register(new RegistrationRequest(
            ORG, REPO, "transaction-eval-consumer", "MAVEN", "service",
            List.of("evaluation-consumers/transaction-eval-consumer/src/main/java"),
            List.of("src/test/java"), null));
    svcId = db.sql("SELECT service_id FROM service_registry WHERE org_id = :org AND repo = :repo LIMIT 1")
            .param("org", ORG)
            .param("repo", REPO)
            .query(String.class)
            .single();

    String consumerId = GraphNodeIds.classNode(svcId, CONSUMER);
    String evalId = GraphNodeIds.classNode(svcId, EVAL_SVC);
    String factoryId = GraphNodeIds.classNode(svcId, FACTORY);
    String defaultId = GraphNodeIds.classNode(svcId, DEFAULT_PROC);
    String methodId = GraphNodeIds.methodNode(svcId, CONSUMER, "processSalesCanonicalEvent");

    nodeRepo.upsert(GraphNode.clazz(consumerId, ORG, REPO, svcId, CONSUMER));
    nodeRepo.upsert(GraphNode.method(methodId, ORG, REPO, svcId, CONSUMER + "#processSalesCanonicalEvent"));
    nodeRepo.upsert(GraphNode.clazz(evalId, ORG, REPO, svcId, EVAL_SVC));
    nodeRepo.upsert(GraphNode.clazz(factoryId, ORG, REPO, svcId, FACTORY));
    nodeRepo.upsert(GraphNode.clazz(defaultId, ORG, REPO, svcId, DEFAULT_PROC));

    edgeRepo.insert(GraphEdge.invokes(consumerId, evalId));
    edgeRepo.insert(GraphEdge.invokes(evalId, factoryId));
    edgeRepo.insert(GraphEdge.routesTo(factoryId, defaultId));

    db.sql("""
            INSERT INTO routing_table_facts
            (org_id, repo, service_id, commit_sha, factory_class_fqn, selector_method, discriminator_type,
             routing_key, target_bean, target_class_fqn, fallback, evidence_source, confidence)
            VALUES (:org, :repo, :svc, :commit, :factory, 'resolveProcessor', 'TransactionSource',
                    'BI_SALES_TRANSACTION', 'defaultTxnEvalProcessor', :target, true, 'test', 0.95)
            """)
            .param("org", ORG)
            .param("repo", REPO)
            .param("svc", svcId)
            .param("commit", COMMIT)
            .param("factory", FACTORY)
            .param("target", DEFAULT_PROC)
            .update();

    db.sql("""
            INSERT INTO entry_trigger_facts
            (org_id, repo, service_id, commit_sha, snapshot_type, trigger_id, trigger_kind, direction,
             env_lane, actor, boundary, path_pattern, linked_handler_fqn, linked_method,
             evidence_source, confidence)
            VALUES (:org, :repo, :svc, :commit, 'BASELINE', 'kafka:pipeline', 'KAFKA_SUBSCRIBE', 'INBOUND',
                    'dev', 'kafka', 'INTERNAL', 'QUOT.SALES.TRANSACTION.PIPELINE.EVENTS', :handler,
                    'processSalesCanonicalEvent', 'KAFKA_LISTENER', 0.95)
            """)
            .param("org", ORG)
            .param("repo", REPO)
            .param("svc", svcId)
            .param("commit", COMMIT)
            .param("handler", CONSUMER)
            .update();
  }

  @Test
  void composeFromHandlerAnchor_includesCoreChain() {
    FlowDiagramModels.ComposeRequest req = new FlowDiagramModels.ComposeRequest(
            ORG, svcId,
            "handlerFqn:" + CONSUMER + ".processSalesCanonicalEvent",
            PKG, 4, false, true, false, "json");

    FlowDiagramModels.FlowDiagramResult result = composer.compose(req);

    assertThat(result.nodes()).isNotEmpty();
    assertThat(result.edges()).isNotEmpty();
    assertThat(result.nodes().stream().map(FlowDiagramModels.FlowDiagramNode::simpleName))
            .anyMatch(n -> n != null && n.contains("TransactionEvalConsumer"));
    assertThat(result.nodes().stream().map(FlowDiagramModels.FlowDiagramNode::simpleName))
            .anyMatch(n -> n != null && n.contains("TransactionEvaluationService"));
    assertThat(result.nodes().stream().map(FlowDiagramModels.FlowDiagramNode::simpleName))
            .anyMatch(n -> n != null && n.contains("ProcessorFactory"));
  }

  @Test
  void composeMermaid_containsProcessorFactory() {
    FlowDiagramModels.ComposeRequest req = new FlowDiagramModels.ComposeRequest(
            ORG, svcId,
            "handlerFqn:" + CONSUMER + ".processSalesCanonicalEvent",
            PKG, 4, false, true, false, "mermaid");

    FlowDiagramModels.FlowDiagramResult result = composer.compose(req);

    assertThat(result.mermaid()).isNotNull();
    assertThat(result.mermaid()).contains("ProcessorFactory");
  }

  @Test
  void composeFromTrigger_includesIngressTopic() {
    FlowDiagramModels.ComposeRequest req = new FlowDiagramModels.ComposeRequest(
            ORG, svcId, "triggerId:kafka:pipeline", PKG, 4, false, true, false, "json");

    FlowDiagramModels.FlowDiagramResult result = composer.compose(req);

    assertThat(result.nodes().stream().filter(n -> "TOPIC".equals(n.kind())).count()).isGreaterThanOrEqualTo(1);
    assertThat(result.edges().stream().anyMatch(e -> "SUBSCRIBES_TO".equals(e.edgeType()))).isTrue();
  }
}
