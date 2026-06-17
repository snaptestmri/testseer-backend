package io.testseer.backend.graph;

import io.testseer.backend.config.RoutingRulePack;
import io.testseer.backend.config.RoutingRulePackLoader;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.ParsedModel;
import io.testseer.backend.ingestion.catalog.ImportIndex;
import io.testseer.backend.ingestion.catalog.TypeFqnResolver;
import io.testseer.backend.ingestion.graph.ListInjectionFactoryRoutingEnricher;
import io.testseer.backend.query.IndexCompleteNotifier;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class GraphFactProjector {

    private static final Logger log = LoggerFactory.getLogger(GraphFactProjector.class);

    private final GraphNodeRepository nodeRepo;
    private final IncrementalEdgeUpdater edgeUpdater;
    private final ServiceRegistryService registryService;
    private final JdbcClient db;
    private final IndexCompleteNotifier indexCompleteNotifier;
    private final TypeFqnResolver typeFqnResolver;
    private final RoutingRulePackLoader routingRulePackLoader;

    public GraphFactProjector(GraphNodeRepository nodeRepo,
                              IncrementalEdgeUpdater edgeUpdater,
                              ServiceRegistryService registryService,
                              JdbcClient db,
                              IndexCompleteNotifier indexCompleteNotifier,
                              TypeFqnResolver typeFqnResolver,
                              RoutingRulePackLoader routingRulePackLoader) {
        this.nodeRepo               = nodeRepo;
        this.edgeUpdater            = edgeUpdater;
        this.registryService        = registryService;
        this.db                     = db;
        this.indexCompleteNotifier  = indexCompleteNotifier;
        this.typeFqnResolver        = typeFqnResolver;
        this.routingRulePackLoader  = routingRulePackLoader;
    }

    @Transactional
    public void project(FactBatch batch, List<ParsedModel> models) {
        project(batch, models, Map.of());
    }

    @Transactional
    public void project(FactBatch batch, List<ParsedModel> models, Map<String, String> sourceByClassFqn) {
        ServiceEntry svc = registryService.getById(batch.serviceId());
        upsertServiceNode(svc);

        Map<String, String> beanIndex = buildBeanIndex(models, routingRulePackLoader.getRulePack());
        List<RoutingRow> routingRows = new ArrayList<>();

        for (ParsedModel model : models) {
            if (model.classFqn() == null || model.parseError()) continue;
            String source = sourceByClassFqn != null ? sourceByClassFqn.get(model.classFqn()) : null;
            projectModel(batch, svc, model, source, beanIndex, routingRows);
        }

        projectListInjectionFactoryRoutes(batch, svc, models, sourceByClassFqn, beanIndex, routingRows);

        projectInterfaceImplBridges(batch, svc, models);

        persistRoutingFacts(batch, routingRows);
        refreshServiceCallEdges(batch.serviceId(), svc.serviceName());
        indexCompleteNotifier.notifyComplete(
                batch.orgId(), batch.repo(), batch.serviceId(), batch.commitSha(), batch.jobId());
        log.debug("Graph projection complete for service {} ({} models)", batch.serviceId(), models.size());
    }

    private void upsertServiceNode(ServiceEntry svc) {
        String moduleType = "library".equals(svc.moduleType()) ? "library" : "service";
        nodeRepo.upsert(new GraphNode(
                GraphNodeIds.serviceNode(svc.serviceId()),
                svc.orgId(),
                svc.repo(),
                svc.serviceName(),
                moduleType,
                "SERVICE",
                null
        ));
    }

    private void projectModel(
            FactBatch batch,
            ServiceEntry svc,
            ParsedModel model,
            String sourceContent,
            Map<String, String> beanIndex,
            List<RoutingRow> routingRows) {

        String classNodeId = GraphNodeIds.classNode(batch.serviceId(), model.classFqn());
        nodeRepo.upsert(GraphNode.clazz(
                classNodeId, svc.orgId(), svc.repo(), svc.serviceName(), model.classFqn()));

        ImportIndex imports = sourceContent != null
                ? ImportIndex.build(sourceContent)
                : ImportIndex.build("package " + packageOf(model.classFqn()) + ";");
        var ctx = new TypeFqnResolver.CompilationContext(batch.orgId(), batch.serviceId(), model.classFqn());

        Set<String> depTypes = new HashSet<>();
        depTypes.addAll(model.constructorParamTypes());
        depTypes.addAll(model.fieldInjectionTypes());
        model.fieldInjections().forEach(f -> depTypes.add(f.declaredType()));

        List<GraphEdge> dependsOnEdges = new ArrayList<>();
        List<GraphEdge> usesTypeEdges = new ArrayList<>();
        for (String depType : depTypes) {
            String depFqn = typeFqnResolver.resolve(depType, imports, ctx).fqn();
            if (!isClassFqn(depFqn) || depFqn.equals(model.classFqn())) continue;

            Optional<String> libraryNode = findLibraryTypeNode(depFqn);
            if (libraryNode.isPresent()) {
                usesTypeEdges.add(GraphEdge.usesType(classNodeId, libraryNode.get()));
                continue;
            }

            String depNodeId = GraphNodeIds.classNode(batch.serviceId(), depFqn);
            nodeRepo.upsert(GraphNode.clazz(
                    depNodeId, svc.orgId(), svc.repo(), svc.serviceName(), depFqn));
            dependsOnEdges.add(GraphEdge.dependsOn(classNodeId, depNodeId));
        }

        edgeUpdater.replaceEdges(classNodeId, "DEPENDS_ON", dependsOnEdges);
        edgeUpdater.replaceEdges(classNodeId, "USES_TYPE", usesTypeEdges);

        List<GraphEdge> invokesEdges = new ArrayList<>();
        if (sourceContent != null) {
            for (ParsedModel.FieldInjectionDef field : model.fieldInjections()) {
                if (!mentionsFieldUse(sourceContent, field.variableName())) continue;
                String depFqn = typeFqnResolver.resolve(field.declaredType(), imports, ctx).fqn();
                if (!isClassFqn(depFqn) || depFqn.equals(model.classFqn())) continue;
                if (findLibraryTypeNode(depFqn).isPresent()) continue;

                String depNodeId = GraphNodeIds.classNode(batch.serviceId(), depFqn);
                nodeRepo.upsert(GraphNode.clazz(
                        depNodeId, svc.orgId(), svc.repo(), svc.serviceName(), depFqn));
                invokesEdges.add(GraphEdge.invokes(classNodeId, depNodeId));
            }
        }

        List<GraphEdge> routesToEdges = new ArrayList<>();
        RoutingRulePack.FactoryRoutingRule factoryMeta =
                factoryMeta(model.classFqn(), routingRulePackLoader.getRulePack());
        for (ParsedModel.FactoryRoutingDef route : model.factoryRouting()) {
            String targetFqn = resolveTargetClassFqn(route, beanIndex, imports, ctx);
            if (!isClassFqn(targetFqn)) continue;

            String targetNodeId = GraphNodeIds.classNode(batch.serviceId(), targetFqn);
            nodeRepo.upsert(GraphNode.clazz(
                    targetNodeId, svc.orgId(), svc.repo(), svc.serviceName(), targetFqn));
            routesToEdges.add(GraphEdge.routesTo(classNodeId, targetNodeId));

            String selector = route.selectorMethod() != null
                    ? route.selectorMethod()
                    : factoryMeta != null ? factoryMeta.selectorMethod() : null;
            String discriminator = route.discriminatorType() != null
                    ? route.discriminatorType()
                    : factoryMeta != null ? factoryMeta.discriminatorType() : null;
            routingRows.add(new RoutingRow(
                    model.classFqn(), selector, discriminator,
                    route.routingKey(), route.targetBean(), targetFqn, route.fallback()));
        }
        edgeUpdater.replaceEdges(classNodeId, "ROUTES_TO", routesToEdges);

        Map<String, List<GraphEdge>> methodInvokeEdges = new LinkedHashMap<>();
        for (ParsedModel.MethodCallDef call : model.methodCalls()) {
            if (!isClassFqn(call.calleeClassFqn())) continue;
            if (findLibraryTypeNode(call.calleeClassFqn()).isPresent()) continue;

            String methodFqn = model.classFqn() + "#" + call.callerMethod();
            String methodNodeId = GraphNodeIds.methodNode(batch.serviceId(), model.classFqn(), call.callerMethod());
            nodeRepo.upsert(GraphNode.method(
                    methodNodeId, svc.orgId(), svc.repo(), svc.serviceName(), methodFqn));

            String calleeNodeId = GraphNodeIds.classNode(batch.serviceId(), call.calleeClassFqn());
            nodeRepo.upsert(GraphNode.clazz(
                    calleeNodeId, svc.orgId(), svc.repo(), svc.serviceName(), call.calleeClassFqn()));

            methodInvokeEdges.computeIfAbsent(methodNodeId, k -> new ArrayList<>())
                    .add(GraphEdge.invokes(methodNodeId, calleeNodeId));
            invokesEdges.add(GraphEdge.invokes(classNodeId, calleeNodeId));
        }
        for (Map.Entry<String, List<GraphEdge>> entry : methodInvokeEdges.entrySet()) {
            edgeUpdater.replaceEdges(entry.getKey(), "INVOKES", entry.getValue());
        }
        edgeUpdater.replaceEdges(classNodeId, "INVOKES", dedupeEdges(invokesEdges));

        List<GraphEdge> outboundEdges = new ArrayList<>();
        for (ParsedModel.EndpointDef ep : model.endpoints()) {
            String endpointFqn = model.classFqn() + "#" + ep.methodName();
            String endpointNodeId = GraphNodeIds.endpointNode(batch.serviceId(), endpointFqn);
            nodeRepo.upsert(GraphNode.endpoint(
                    endpointNodeId, svc.orgId(), svc.repo(), svc.serviceName(), endpointFqn));
        }

        for (ParsedModel.OutboundCallDef call : model.outboundCalls()) {
            findEndpointNodeByHttp(call.httpMethod(), call.path())
                    .ifPresent(targetId -> outboundEdges.add(
                            GraphEdge.outboundTo(classNodeId, targetId)));
        }
        edgeUpdater.replaceEdges(classNodeId, "OUTBOUND_TO", outboundEdges);
    }

    private void projectListInjectionFactoryRoutes(
            FactBatch batch,
            ServiceEntry svc,
            List<ParsedModel> models,
            Map<String, String> sourceByClassFqn,
            Map<String, String> beanIndex,
            List<RoutingRow> routingRows) {

        Map<String, List<ParsedModel.FactoryRoutingDef>> byFactory =
                ListInjectionFactoryRoutingEnricher.enrichByFactory(models, sourceByClassFqn);

        for (Map.Entry<String, List<ParsedModel.FactoryRoutingDef>> entry : byFactory.entrySet()) {
            String factoryFqn = entry.getKey();
            String classNodeId = GraphNodeIds.classNode(batch.serviceId(), factoryFqn);
            nodeRepo.upsert(GraphNode.clazz(
                    classNodeId, svc.orgId(), svc.repo(), svc.serviceName(), factoryFqn));

            RoutingRulePack.FactoryRoutingRule factoryMeta =
                    factoryMeta(factoryFqn, routingRulePackLoader.getRulePack());
            List<GraphEdge> routesToEdges = new ArrayList<>();

            for (ParsedModel.FactoryRoutingDef route : entry.getValue()) {
                String targetFqn = route.targetClassFqn();
                if (!isClassFqn(targetFqn)) {
                    continue;
                }
                String targetNodeId = GraphNodeIds.classNode(batch.serviceId(), targetFqn);
                nodeRepo.upsert(GraphNode.clazz(
                        targetNodeId, svc.orgId(), svc.repo(), svc.serviceName(), targetFqn));
                routesToEdges.add(GraphEdge.routesTo(classNodeId, targetNodeId));

                String selector = route.selectorMethod() != null
                        ? route.selectorMethod()
                        : factoryMeta != null ? factoryMeta.selectorMethod() : null;
                String discriminator = route.discriminatorType() != null
                        ? route.discriminatorType()
                        : factoryMeta != null ? factoryMeta.discriminatorType() : null;
                routingRows.add(new RoutingRow(
                        factoryFqn, selector, discriminator,
                        route.routingKey(), route.targetBean(), targetFqn, route.fallback()));
            }
            if (!routesToEdges.isEmpty()) {
                edgeUpdater.replaceEdges(classNodeId, "ROUTES_TO", routesToEdges);
            }
        }
    }

    /**
     * GRP-19: bridge REST API interfaces to {@code @RestController} implementations for reachability seeding.
     */
    private void projectInterfaceImplBridges(FactBatch batch, ServiceEntry svc, List<ParsedModel> models) {
        Map<String, ParsedModel> byFqn = new LinkedHashMap<>();
        for (ParsedModel model : models) {
            if (model.classFqn() != null) {
                byFqn.put(model.classFqn(), model);
            }
        }

        for (ParsedModel model : models) {
            if (model.classFqn() == null || !isRestController(model)) {
                continue;
            }
            String implNodeId = GraphNodeIds.classNode(batch.serviceId(), model.classFqn());
            nodeRepo.upsert(GraphNode.clazz(
                    implNodeId, svc.orgId(), svc.repo(), svc.serviceName(), model.classFqn()));

            for (String ifaceType : model.implementedInterfaces()) {
                String ifaceFqn = resolveInterfaceFqn(ifaceType, model.classFqn(), byFqn);
                if (!isClassFqn(ifaceFqn) || ifaceFqn.equals(model.classFqn())) {
                    continue;
                }
                String ifaceNodeId = GraphNodeIds.classNode(batch.serviceId(), ifaceFqn);
                nodeRepo.upsert(GraphNode.clazz(
                        ifaceNodeId, svc.orgId(), svc.repo(), svc.serviceName(), ifaceFqn));
                edgeUpdater.replaceEdges(
                        ifaceNodeId,
                        "IMPLEMENTS",
                        List.of(GraphEdge.implementsEdge(ifaceNodeId, implNodeId)));

                bridgeInterfaceMethodInvokes(batch, svc, model, ifaceFqn, ifaceNodeId, implNodeId);
            }
        }
    }

    private void bridgeInterfaceMethodInvokes(
            FactBatch batch,
            ServiceEntry svc,
            ParsedModel impl,
            String ifaceFqn,
            String ifaceNodeId,
            String implNodeId) {
        Map<String, List<GraphEdge>> ifaceMethodEdges = new LinkedHashMap<>();
        List<GraphEdge> ifaceClassEdges = new ArrayList<>();

        for (ParsedModel.MethodCallDef call : impl.methodCalls()) {
            if (!isClassFqn(call.calleeClassFqn())) {
                continue;
            }
            String ifaceMethodNodeId = GraphNodeIds.methodNode(
                    batch.serviceId(), ifaceFqn, call.callerMethod());
            nodeRepo.upsert(GraphNode.method(
                    ifaceMethodNodeId, svc.orgId(), svc.repo(), svc.serviceName(),
                    ifaceFqn + "#" + call.callerMethod()));

            String calleeNodeId = GraphNodeIds.classNode(batch.serviceId(), call.calleeClassFqn());
            nodeRepo.upsert(GraphNode.clazz(
                    calleeNodeId, svc.orgId(), svc.repo(), svc.serviceName(), call.calleeClassFqn()));

            ifaceMethodEdges.computeIfAbsent(ifaceMethodNodeId, k -> new ArrayList<>())
                    .add(GraphEdge.invokes(ifaceMethodNodeId, calleeNodeId));
            ifaceClassEdges.add(GraphEdge.invokes(ifaceNodeId, calleeNodeId));
        }

        for (Map.Entry<String, List<GraphEdge>> entry : ifaceMethodEdges.entrySet()) {
            edgeUpdater.replaceEdges(entry.getKey(), "INVOKES", entry.getValue());
        }
        if (!ifaceClassEdges.isEmpty()) {
            edgeUpdater.replaceEdges(ifaceNodeId, "INVOKES", dedupeEdges(ifaceClassEdges));
        }
    }

    private static String resolveInterfaceFqn(
            String ifaceType, String implClassFqn, Map<String, ParsedModel> byFqn) {
        if (ifaceType == null || ifaceType.isBlank()) {
            return ifaceType;
        }
        if (ifaceType.contains(".") && byFqn.containsKey(ifaceType)) {
            return ifaceType;
        }
        String pkg = packageOf(implClassFqn);
        String candidate = pkg.isBlank() ? ifaceType : pkg + "." + ifaceType;
        if (byFqn.containsKey(candidate)) {
            return candidate;
        }
        return candidate;
    }

    private static boolean isRestController(ParsedModel model) {
        return model.annotations().stream()
                .anyMatch(a -> "RestController".equals(a) || "Controller".equals(a));
    }

    private static List<GraphEdge> dedupeEdges(List<GraphEdge> edges) {
        Map<String, GraphEdge> deduped = new LinkedHashMap<>();
        for (GraphEdge edge : edges) {
            deduped.putIfAbsent(edge.fromNode() + "->" + edge.toNode(), edge);
        }
        return List.copyOf(deduped.values());
    }

    private void persistRoutingFacts(FactBatch batch, List<RoutingRow> rows) {
        db.sql("""
                DELETE FROM routing_table_facts
                WHERE org_id = :orgId AND service_id = :serviceId AND commit_sha = :commitSha
                """)
                .param("orgId", batch.orgId())
                .param("serviceId", batch.serviceId())
                .param("commitSha", batch.commitSha())
                .update();

        if (rows.isEmpty()) {
            return;
        }

        String sql = """
                INSERT INTO routing_table_facts
                  (org_id, repo, service_id, commit_sha, snapshot_type, factory_class_fqn,
                   selector_method, discriminator_type, routing_key, target_bean, target_class_fqn,
                   fallback, evidence_source, confidence)
                VALUES (:orgId, :repo, :serviceId, :commitSha, :snapshotType, :factoryFqn,
                        :selectorMethod, :discriminatorType, :routingKey, :targetBean, :targetFqn,
                        :fallback, :evidenceSource, :confidence)
                """;
        for (RoutingRow row : rows) {
            db.sql(sql)
                    .param("orgId", batch.orgId())
                    .param("repo", batch.repo())
                    .param("serviceId", batch.serviceId())
                    .param("commitSha", batch.commitSha())
                    .param("snapshotType", batch.snapshotType())
                    .param("factoryFqn", row.factoryClassFqn())
                    .param("selectorMethod", row.selectorMethod())
                    .param("discriminatorType", row.discriminatorType())
                    .param("routingKey", row.routingKey())
                    .param("targetBean", row.targetBean())
                    .param("targetFqn", row.targetClassFqn())
                    .param("fallback", row.fallback())
                    .param("evidenceSource", "factory-routing")
                    .param("confidence", 0.95)
                    .update();
        }
    }

    static Map<String, String> buildBeanIndex(List<ParsedModel> models, RoutingRulePack rulePack) {
        Map<String, String> index = new HashMap<>();
        for (RoutingRulePack.BeanLinkRule link : rulePack.beanLinks()) {
            index.put(link.beanName(), link.classFqn());
        }
        for (ParsedModel model : models) {
            if (model.componentBeanName() != null && model.classFqn() != null) {
                index.putIfAbsent(model.componentBeanName(), model.classFqn());
            }
            if (model.classFqn() != null) {
                String defaultBean = Character.toLowerCase(model.classFqn().charAt(
                        model.classFqn().lastIndexOf('.') + 1))
                        + simpleName(model.classFqn()).substring(1);
                index.putIfAbsent(defaultBean, model.classFqn());
            }
        }
        return index;
    }

    private String resolveTargetClassFqn(
            ParsedModel.FactoryRoutingDef route,
            Map<String, String> beanIndex,
            ImportIndex imports,
            TypeFqnResolver.CompilationContext ctx) {
        if (route.targetBean() != null && beanIndex.containsKey(route.targetBean())) {
            return beanIndex.get(route.targetBean());
        }
        if (route.targetClassFqn() != null && route.targetClassFqn().contains(".")) {
            return route.targetClassFqn();
        }
        if (route.targetClassFqn() != null) {
            return typeFqnResolver.resolve(route.targetClassFqn(), imports, ctx).fqn();
        }
        return null;
    }

    private static RoutingRulePack.FactoryRoutingRule factoryMeta(
            String classFqn, RoutingRulePack rulePack) {
        return rulePack.factoryRouting().stream()
                .filter(r -> classFqn.equals(r.factoryFqn()))
                .findFirst()
                .orElse(null);
    }

    private void refreshServiceCallEdges(String serviceId, String serviceName) {
        List<String> targetServices = db.sql("""
                SELECT DISTINCT gn_to.service
                FROM graph_edges e
                JOIN graph_nodes gn_from ON e.from_node = gn_from.id
                JOIN graph_nodes gn_to   ON e.to_node   = gn_to.id
                WHERE e.edge_type = 'OUTBOUND_TO'
                  AND gn_from.service = :serviceName
                  AND gn_to.service <> :serviceName
                """)
                .param("serviceName", serviceName)
                .query(String.class)
                .list();

        String serviceNodeId = GraphNodeIds.serviceNode(serviceId);
        List<GraphEdge> calls = new ArrayList<>();
        for (String targetService : targetServices) {
            findServiceNodeIdByName(targetService).ifPresent(targetId ->
                    calls.add(GraphEdge.calls(serviceNodeId, targetId)));
        }
        edgeUpdater.replaceEdges(serviceNodeId, "CALLS", calls);
    }

    private Optional<String> findLibraryTypeNode(String typeFqn) {
        return db.sql("""
                SELECT id FROM graph_nodes
                WHERE symbol_fqn = :fqn AND module_type = 'library'
                LIMIT 1
                """)
                .param("fqn", typeFqn)
                .query(String.class)
                .optional();
    }

    private Optional<String> findEndpointNodeByHttp(String httpMethod, String path) {
        if (httpMethod == null || path == null) return Optional.empty();
        return db.sql("""
                SELECT gn.id
                FROM symbol_facts sf
                JOIN graph_nodes gn ON gn.symbol_fqn = sf.symbol_fqn AND gn.node_type = 'ENDPOINT'
                WHERE sf.symbol_kind = 'ENDPOINT'
                  AND sf.attributes->>'httpMethod' = :method
                  AND sf.attributes->>'path' = :path
                ORDER BY sf.indexed_at DESC
                LIMIT 1
                """)
                .param("method", httpMethod)
                .param("path", path)
                .query(String.class)
                .optional();
    }

    private Optional<String> findServiceNodeIdByName(String serviceName) {
        return db.sql("""
                SELECT gn.id
                FROM graph_nodes gn
                JOIN service_registry sr ON sr.service_id = gn.id
                WHERE sr.service_name = :name AND gn.node_type = 'SERVICE'
                LIMIT 1
                """)
                .param("name", serviceName)
                .query(String.class)
                .optional();
    }

    static String resolveTypeFqn(String typeName, String owningClassFqn) {
        return TypeFqnResolver.samePackageFallback(typeName, owningClassFqn);
    }

    private static String packageOf(String classFqn) {
        int dot = classFqn != null ? classFqn.lastIndexOf('.') : -1;
        return dot >= 0 ? classFqn.substring(0, dot) : "";
    }

    private static String simpleName(String classFqn) {
        int dot = classFqn.lastIndexOf('.');
        return dot >= 0 ? classFqn.substring(dot + 1) : classFqn;
    }

    private static boolean mentionsFieldUse(String content, String variableName) {
        return content != null && variableName != null && content.contains(variableName + ".");
    }

    /** Reject parser noise (chained expressions, generics text) from becoming graph class nodes. */
    static boolean isClassFqn(String fqn) {
        if (fqn == null || fqn.isBlank() || fqn.length() > 500) {
            return false;
        }
        return fqn.matches("[\\w.$]+");
    }

    private record RoutingRow(
            String factoryClassFqn,
            String selectorMethod,
            String discriminatorType,
            String routingKey,
            String targetBean,
            String targetClassFqn,
            boolean fallback
    ) {}
}
