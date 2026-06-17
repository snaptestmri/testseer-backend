package io.testseer.backend.ingestion.triggers;

import io.testseer.backend.config.TriggerRulePackLoader;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.ParsedModel;
import io.testseer.backend.ingestion.messaging.MessagingFactOrchestrator;
import io.testseer.backend.ingestion.messaging.YamlPubSubExtractor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EntryTriggerOrchestrator {

    private final InboundRestTriggerExtractor inboundRestTriggerExtractor;
    private final SpringCronTriggerExtractor springCronTriggerExtractor;
    private final GcsTriggerExtractor gcsTriggerExtractor;
    private final K8sCronTriggerExtractor k8sCronTriggerExtractor;
    private final PubSubSubscribeTriggerExtractor pubSubSubscribeTriggerExtractor;
    private final KafkaSubscribeTriggerExtractor kafkaSubscribeTriggerExtractor;
    private final KafkaListenerTriggerExtractor kafkaListenerTriggerExtractor;
    private final AirflowDagTriggerExtractor airflowDagTriggerExtractor;
    private final BatchLauncherTriggerExtractor batchLauncherTriggerExtractor;
    private final SpringBootMainTriggerExtractor springBootMainTriggerExtractor;
    private final SpringBootMainDeploymentLinker springBootMainDeploymentLinker;
    private final CronHandlerLinker cronHandlerLinker;
    private final TriggerRulePackLoader rulePackLoader;

    public EntryTriggerOrchestrator(
            InboundRestTriggerExtractor inboundRestTriggerExtractor,
            SpringCronTriggerExtractor springCronTriggerExtractor,
            GcsTriggerExtractor gcsTriggerExtractor,
            K8sCronTriggerExtractor k8sCronTriggerExtractor,
            PubSubSubscribeTriggerExtractor pubSubSubscribeTriggerExtractor,
            KafkaSubscribeTriggerExtractor kafkaSubscribeTriggerExtractor,
            KafkaListenerTriggerExtractor kafkaListenerTriggerExtractor,
            AirflowDagTriggerExtractor airflowDagTriggerExtractor,
            BatchLauncherTriggerExtractor batchLauncherTriggerExtractor,
            SpringBootMainTriggerExtractor springBootMainTriggerExtractor,
            SpringBootMainDeploymentLinker springBootMainDeploymentLinker,
            CronHandlerLinker cronHandlerLinker,
            TriggerRulePackLoader rulePackLoader) {
        this.inboundRestTriggerExtractor = inboundRestTriggerExtractor;
        this.springCronTriggerExtractor = springCronTriggerExtractor;
        this.gcsTriggerExtractor = gcsTriggerExtractor;
        this.k8sCronTriggerExtractor = k8sCronTriggerExtractor;
        this.pubSubSubscribeTriggerExtractor = pubSubSubscribeTriggerExtractor;
        this.kafkaSubscribeTriggerExtractor = kafkaSubscribeTriggerExtractor;
        this.kafkaListenerTriggerExtractor = kafkaListenerTriggerExtractor;
        this.airflowDagTriggerExtractor = airflowDagTriggerExtractor;
        this.batchLauncherTriggerExtractor = batchLauncherTriggerExtractor;
        this.springBootMainTriggerExtractor = springBootMainTriggerExtractor;
        this.springBootMainDeploymentLinker = springBootMainDeploymentLinker;
        this.cronHandlerLinker = cronHandlerLinker;
        this.rulePackLoader = rulePackLoader;
    }

    public List<FactBatch.EntryTriggerFact> buildFromModels(
            List<ParsedModel> models, String defaultEnvLane) {
        return buildFromModels(models, List.of(), List.of(), List.of(), defaultEnvLane, null, null);
    }

    public List<FactBatch.EntryTriggerFact> buildFromModels(
            List<ParsedModel> models,
            List<MessagingFactOrchestrator.SourceFile> sources,
            List<YamlPubSubExtractor.ConfigFile> configFiles,
            List<FactBatch.PubSubResourceFact> pubsubFacts,
            String defaultEnvLane) {
        return buildFromModels(models, sources, configFiles, pubsubFacts, defaultEnvLane, null, null);
    }

    public List<FactBatch.EntryTriggerFact> buildFromModels(
            List<ParsedModel> models,
            List<MessagingFactOrchestrator.SourceFile> sources,
            List<YamlPubSubExtractor.ConfigFile> configFiles,
            List<FactBatch.PubSubResourceFact> pubsubFacts,
            String defaultEnvLane,
            String serviceName,
            String repo) {
        if (models == null || models.isEmpty()) {
            return List.of();
        }
        Map<String, String> contentByPath = new LinkedHashMap<>();
        if (sources != null) {
            for (MessagingFactOrchestrator.SourceFile source : sources) {
                contentByPath.put(source.path(), source.content());
            }
        }

        List<FactBatch.EntryTriggerFact> merged = new ArrayList<>();
        java.util.Set<String> seenTriggerIds = new java.util.LinkedHashSet<>();
        addTriggers(merged, seenTriggerIds, inboundRestTriggerExtractor.extract(
                models, rulePackLoader.getRulePack(), defaultEnvLane));
        addTriggers(merged, seenTriggerIds, springCronTriggerExtractor.extract(models, contentByPath, defaultEnvLane));
        addTriggers(merged, seenTriggerIds, gcsTriggerExtractor.extract(models, contentByPath, defaultEnvLane));
        addTriggers(merged, seenTriggerIds, k8sCronTriggerExtractor.extract(configFiles, defaultEnvLane));
        addTriggers(merged, seenTriggerIds, pubSubSubscribeTriggerExtractor.extract(pubsubFacts, defaultEnvLane));
        addTriggers(merged, seenTriggerIds, kafkaSubscribeTriggerExtractor.extract(pubsubFacts, defaultEnvLane));
        addTriggers(merged, seenTriggerIds, kafkaListenerTriggerExtractor.extract(
                models, contentByPath, configFiles, defaultEnvLane));
        addTriggers(merged, seenTriggerIds, airflowDagTriggerExtractor.extract(
                contentByPath, rulePackLoader.getRulePack(), defaultEnvLane, serviceName, repo));
        addTriggers(merged, seenTriggerIds, batchLauncherTriggerExtractor.extract(models, contentByPath, defaultEnvLane));
        Map<String, String> pomHints = MavenMainClassResolver.resolveFromContentByPath(contentByPath);
        addTriggers(merged, seenTriggerIds, springBootMainTriggerExtractor.extract(
                models, contentByPath, defaultEnvLane, pomHints));
        List<FactBatch.EntryTriggerFact> withDeployments =
                springBootMainDeploymentLinker.link(merged, configFiles);
        return cronHandlerLinker.link(withDeployments, models, contentByPath);
    }

    private static void addTriggers(
            List<FactBatch.EntryTriggerFact> merged,
            java.util.Set<String> seenTriggerIds,
            List<FactBatch.EntryTriggerFact> extracted) {
        for (FactBatch.EntryTriggerFact fact : extracted) {
            if (seenTriggerIds.add(fact.triggerId())) {
                merged.add(fact);
            }
        }
    }
}
