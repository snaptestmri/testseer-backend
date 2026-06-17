package io.testseer.backend.ingestion.messaging;

import io.testseer.backend.config.MessagingRulePackLoader;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.FactExtractor;
import io.testseer.backend.ingestion.CrossModuleOutboundAttributor;
import io.testseer.backend.ingestion.ParsedModel;
import io.testseer.backend.ingestion.PeripheralDetector;
import io.testseer.backend.ingestion.catalog.BigQueryDirectExtractor;
import io.testseer.backend.ingestion.catalog.HandlerAccessLinker;
import io.testseer.backend.ingestion.catalog.MongoAccessExtractor;
import io.testseer.backend.ingestion.external.ExternalEndpointLinker;
import io.testseer.backend.query.HandlerScopeFilter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MessagingFactOrchestrator {

    private final FactExtractor factExtractor;
    private final PeripheralDetector peripheralDetector;
    private final YamlPubSubExtractor yamlPubSubExtractor;
    private final YamlKafkaTopicExtractor yamlKafkaTopicExtractor;
    private final ProtoSchemaExtractor protoSchemaExtractor;
    private final MessagingClassLinker messagingClassLinker;
    private final DataAccessExtractor dataAccessExtractor;
    private final HandlerAccessLinker handlerAccessLinker;
    private final MongoAccessExtractor mongoAccessExtractor;
    private final BigQueryDirectExtractor bigQueryDirectExtractor;
    private final FlowGateExtractor flowGateExtractor;
    private final ValidationHintBuilder validationHintBuilder;
    private final ExternalEndpointLinker externalEndpointLinker;
    private final HttpPubSubPublishLinker httpPubSubPublishLinker;
    private final MessagingRulePackLoader rulePackLoader;
    private final CrossModuleOutboundAttributor crossModuleOutboundAttributor;
    private final KafkaPublishOutboundExtractor kafkaPublishOutboundExtractor;
    private final PubSubPublishOutboundExtractor pubSubPublishOutboundExtractor;

    public MessagingFactOrchestrator(
            FactExtractor factExtractor,
            PeripheralDetector peripheralDetector,
            YamlPubSubExtractor yamlPubSubExtractor,
            YamlKafkaTopicExtractor yamlKafkaTopicExtractor,
            ProtoSchemaExtractor protoSchemaExtractor,
            MessagingClassLinker messagingClassLinker,
            DataAccessExtractor dataAccessExtractor,
            HandlerAccessLinker handlerAccessLinker,
            MongoAccessExtractor mongoAccessExtractor,
            BigQueryDirectExtractor bigQueryDirectExtractor,
            FlowGateExtractor flowGateExtractor,
            ValidationHintBuilder validationHintBuilder,
            ExternalEndpointLinker externalEndpointLinker,
            HttpPubSubPublishLinker httpPubSubPublishLinker,
            MessagingRulePackLoader rulePackLoader,
            CrossModuleOutboundAttributor crossModuleOutboundAttributor,
            KafkaPublishOutboundExtractor kafkaPublishOutboundExtractor,
            PubSubPublishOutboundExtractor pubSubPublishOutboundExtractor) {
        this.factExtractor = factExtractor;
        this.peripheralDetector = peripheralDetector;
        this.yamlPubSubExtractor = yamlPubSubExtractor;
        this.yamlKafkaTopicExtractor = yamlKafkaTopicExtractor;
        this.protoSchemaExtractor = protoSchemaExtractor;
        this.messagingClassLinker = messagingClassLinker;
        this.dataAccessExtractor = dataAccessExtractor;
        this.handlerAccessLinker = handlerAccessLinker;
        this.mongoAccessExtractor = mongoAccessExtractor;
        this.bigQueryDirectExtractor = bigQueryDirectExtractor;
        this.flowGateExtractor = flowGateExtractor;
        this.validationHintBuilder = validationHintBuilder;
        this.externalEndpointLinker = externalEndpointLinker;
        this.httpPubSubPublishLinker = httpPubSubPublishLinker;
        this.rulePackLoader = rulePackLoader;
        this.crossModuleOutboundAttributor = crossModuleOutboundAttributor;
        this.kafkaPublishOutboundExtractor = kafkaPublishOutboundExtractor;
        this.pubSubPublishOutboundExtractor = pubSubPublishOutboundExtractor;
    }

    public IndexingFacts buildFacts(
            String jobId,
            String orgId,
            String repo,
            String serviceId,
            String commitSha,
            String snapshotType,
            List<SourceFile> javaFiles,
            List<YamlPubSubExtractor.ConfigFile> configFiles) {

        List<ParsedModel> models = javaFiles.stream()
                .map(f -> f.parsedModel())
                .toList();

        List<FactBatch.SymbolFact> symbolFacts = new ArrayList<>();
        for (ParsedModel m : models) {
            symbolFacts.addAll(factExtractor.extractSymbolFacts(m));
            symbolFacts.addAll(factExtractor.extractMethodFacts(m));
            symbolFacts.addAll(factExtractor.extractEnumFacts(m));
        }

        List<FactBatch.OutboundCallFact> javaparserOutbound = models.stream()
                .flatMap(m -> factExtractor.extractOutboundCallFacts(m).stream())
                .toList();
        List<FactBatch.PeripheralFact> peripheralFacts = models.stream()
                .flatMap(m -> peripheralDetector.detect(m).stream())
                .toList();
        List<FactBatch.UnsupportedConstructFact> unsupported = models.stream()
                .flatMap(m -> factExtractor.extractUnsupportedConstructFacts(m).stream())
                .toList();

        List<YamlPubSubExtractor.ConfigFile> yamlFiles = configFiles.stream()
                .filter(f -> f.path().endsWith(".yaml") || f.path().endsWith(".yml"))
                .toList();
        List<YamlPubSubExtractor.ConfigFile> protoFiles = configFiles.stream()
                .filter(f -> f.path().endsWith(".proto"))
                .toList();

        List<ProtoSchemaExtractor.JavaSourceFile> javaSources = javaFiles.stream()
                .map(f -> new ProtoSchemaExtractor.JavaSourceFile(
                        f.path(), f.content(), f.parsedModel().classFqn()))
                .toList();
        List<ProtoSchemaExtractor.JavaSourceFile> productionSources = javaSources.stream()
                .filter(f -> !HandlerScopeFilter.isTestSourcePath(f.path()))
                .toList();

        List<FactBatch.PubSubResourceFact> pubsub = yamlPubSubExtractor.extract(yamlFiles);
        pubsub.addAll(yamlKafkaTopicExtractor.extract(yamlFiles));
        pubsub = messagingClassLinker.linkPubSub(pubsub, javaSources, rulePackLoader.getRulePack());

        Map<String, String> sourceByClassFqn = new LinkedHashMap<>();
        for (ProtoSchemaExtractor.JavaSourceFile jf : javaSources) {
            if (jf.classFqn() != null && jf.content() != null) {
                sourceByClassFqn.put(jf.classFqn(), jf.content());
            }
        }

        List<FactBatch.OutboundCallFact> outboundFacts = crossModuleOutboundAttributor.attributeToCallers(
                models,
                mergeOutboundFacts(
                        javaparserOutbound,
                        kafkaPublishOutboundExtractor.extract(models, pubsub),
                        pubSubPublishOutboundExtractor.extract(models, pubsub, sourceByClassFqn)));

        var protoCatalog = protoSchemaExtractor.extractCatalog(protoFiles);
        List<FactBatch.MessageSchemaFact> schemas =
                protoSchemaExtractor.extractFromJava(javaSources, protoCatalog);
        schemas = messagingClassLinker.linkSchemasToTopics(schemas, pubsub);

        List<FactBatch.DataAccessFact> dataAccess = mergeDataAccess(
                handlerAccessLinker.extract(orgId, serviceId, productionSources),
                mongoAccessExtractor.extract(orgId, serviceId, productionSources),
                bigQueryDirectExtractor.extract(orgId, serviceId, productionSources));
        if (dataAccess.isEmpty()) {
            dataAccess = dataAccessExtractor.extract(productionSources);
        }
        List<FactBatch.FlowGateFact> gates = flowGateExtractor.extract(models, javaSources, yamlFiles);
        List<FactBatch.ValidationHintFact> hints =
                validationHintBuilder.build(pubsub, dataAccess, gates, "pdn");

        ExternalEndpointLinker.LinkedExternalFacts external =
                externalEndpointLinker.link(javaFiles, models, configFiles, "pdn");
        pubsub.addAll(httpPubSubPublishLinker.link(
                javaFiles, models, yamlFiles, external.endpoints(), external.callSites(),
                rulePackLoader.getRulePack()));
        List<FactBatch.ValidationHintFact> allHints = new ArrayList<>(hints);
        allHints.addAll(external.hints());

        FactBatch batch = FactBatch.create(
                jobId, orgId, repo, serviceId, commitSha, snapshotType,
                symbolFacts, outboundFacts, peripheralFacts, unsupported,
                pubsub, schemas, dataAccess, gates, allHints,
                external.endpoints(), external.callSites()
        );

        return new IndexingFacts(models, batch);
    }

    @SafeVarargs
    private static List<FactBatch.DataAccessFact> mergeDataAccess(List<FactBatch.DataAccessFact>... lists) {
        java.util.Map<String, FactBatch.DataAccessFact> byKey = new java.util.LinkedHashMap<>();
        for (List<FactBatch.DataAccessFact> list : lists) {
            for (FactBatch.DataAccessFact fact : list) {
                String key = fact.handlerClassFqn() + "|" + fact.handlerMethod() + "|"
                        + (fact.accessorFqn() != null ? fact.accessorFqn() : fact.repositoryFqn()) + "|"
                        + fact.daoMethod();
                byKey.merge(key, fact, (left, right) -> left.confidence() >= right.confidence() ? left : right);
            }
        }
        return List.copyOf(byKey.values());
    }

    private static List<FactBatch.OutboundCallFact> mergeOutboundFacts(
            List<FactBatch.OutboundCallFact> primary,
            List<FactBatch.OutboundCallFact> supplemental,
            List<FactBatch.OutboundCallFact> pubsubOutbound) {
        java.util.Map<String, FactBatch.OutboundCallFact> merged = new java.util.LinkedHashMap<>();
        for (FactBatch.OutboundCallFact fact : primary) {
            merged.putIfAbsent(outboundDedupeKey(fact), fact);
        }
        for (FactBatch.OutboundCallFact fact : supplemental) {
            merged.putIfAbsent(outboundDedupeKey(fact), fact);
        }
        for (FactBatch.OutboundCallFact fact : pubsubOutbound) {
            merged.putIfAbsent(outboundDedupeKey(fact), fact);
        }
        return List.copyOf(merged.values());
    }

    private static List<FactBatch.OutboundCallFact> mergeOutboundFacts(
            List<FactBatch.OutboundCallFact> primary,
            List<FactBatch.OutboundCallFact> supplemental) {
        return mergeOutboundFacts(primary, supplemental, List.of());
    }

    private static String outboundDedupeKey(FactBatch.OutboundCallFact fact) {
        return fact.sourceSymbol() + "|" + fact.httpMethod() + "|" + fact.path();
    }

    public record SourceFile(String path, String content, ParsedModel parsedModel) {}

    public record IndexingFacts(List<ParsedModel> models, FactBatch batch) {}
}
