package io.testseer.backend.query;

import io.testseer.backend.graph.GraphNodeIds;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GraphQueryControllerReachabilityTest {

  @Test
  void resolveReachabilityStartNode_classFromSymbolFqn() {
    String nodeId = GraphQueryController.resolveReachabilityStartNode(
        "svc-001", "class", null,
        "com.example.TransactionEvaluationService", null);
    assertThat(nodeId).isEqualTo(
        GraphNodeIds.classNode("svc-001", "com.example.TransactionEvaluationService"));
  }

  @Test
  void resolveReachabilityStartNode_methodFromSymbolFqnAndMethodName() {
    String nodeId = GraphQueryController.resolveReachabilityStartNode(
        "svc-001", "method", null,
        "com.example.TransactionEvalConsumer", "processSalesCanonicalEvent");
    assertThat(nodeId).isEqualTo(GraphNodeIds.methodNode(
        "svc-001", "com.example.TransactionEvalConsumer", "processSalesCanonicalEvent"));
  }

  @Test
  void resolveReachabilityStartNode_classWithoutSymbolReturnsNull() {
    assertThat(GraphQueryController.resolveReachabilityStartNode(
        "svc-001", "class", null, null, null)).isNull();
  }

  @Test
  void resolveReachabilityStartNode_serviceUsesServiceNode() {
    assertThat(GraphQueryController.resolveReachabilityStartNode(
        "svc-001", "service", null, null, null))
        .isEqualTo(GraphNodeIds.serviceNode("svc-001"));
  }
}
