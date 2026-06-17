package io.testseer.backend.ingestion;

import io.testseer.backend.ingestion.messaging.KafkaPublishOutboundExtractor;
import io.testseer.backend.ingestion.messaging.MessagingClassLinker;
import io.testseer.backend.ingestion.messaging.ProtoSchemaExtractor;
import io.testseer.backend.ingestion.messaging.YamlKafkaTopicExtractor;
import io.testseer.backend.ingestion.messaging.YamlPubSubExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaUserProfileRealSourceIT {

    private static final Path REPO = Path.of(System.getProperty("user.home"),
            "Documents/GitHub/platform-user-profile");
    private static final String PRODUCER_FQN =
            "com.quotient.platform.userprofile.producer.UserEmailAcceptanceRedeemEventProducer";

    static boolean userProfileRepoPresent() {
        return Files.isRegularFile(REPO.resolve(
                "src/main/java/com/quotient/platform/userprofile/producer/UserEmailAcceptanceRedeemEventProducer.java"));
    }

    private final JavaParserService parser = new JavaParserService();
    private final YamlKafkaTopicExtractor kafkaYaml = new YamlKafkaTopicExtractor();
    private final MessagingClassLinker linker = new MessagingClassLinker();
    private final KafkaPublishOutboundExtractor outbound = new KafkaPublishOutboundExtractor();

    @Test
    @EnabledIf("userProfileRepoPresent")
    void realProducerSource_emitsKafkaOutbound() throws Exception {
        Path producerPath = REPO.resolve(
                "src/main/java/com/quotient/platform/userprofile/producer/UserEmailAcceptanceRedeemEventProducer.java");
        Path yamlPath = REPO.resolve(
                "kubernetes-manifests/dev/user-profile-service.dev.config-map.yaml");

        String source = Files.readString(producerPath);
        String yaml = Files.readString(yamlPath);

        ParsedModel model = parser.parse(producerPath.toString(), source);
        assertThat(model.classFqn()).isEqualTo(PRODUCER_FQN);
        assertThat(model.fieldInjections()).anyMatch(f ->
                "rebateRedeemSyncProducer".equals(f.variableName())
                        && f.declaredType().contains("SyncProducer"));
        assertThat(model.methodCalls()).anyMatch(c ->
                "publishEvent".equals(c.callerMethod())
                        && "send".equals(c.calleeMethod())
                        && "rebateRedeemSyncProducer".equals(c.calleeVariable()));

        List<FactBatch.PubSubResourceFact> kafkaTopics = kafkaYaml.extract(List.of(
                new YamlPubSubExtractor.ConfigFile(yamlPath.toString(), yaml)));
        assertThat(kafkaTopics).anyMatch(t ->
                "QUOT.REBATE.REDEEM.EVENTS".equals(t.shortId()) && "PUBLISH".equals(t.role()));

        var javaSources = List.of(new ProtoSchemaExtractor.JavaSourceFile(
                producerPath.toString(), source, model.classFqn()));
        List<FactBatch.PubSubResourceFact> linked = linker.linkPubSub(kafkaTopics, javaSources);
        assertThat(linked).anyMatch(t ->
                PRODUCER_FQN.equals(t.linkedClassFqn()) && "QUOT.REBATE.REDEEM.EVENTS".equals(t.shortId()));

        List<FactBatch.OutboundCallFact> facts = outbound.extract(List.of(model), linked);
        assertThat(facts).anyMatch(f ->
                f.sourceSymbol().startsWith(PRODUCER_FQN)
                        && "KAFKA".equals(f.httpMethod())
                        && "QUOT.REBATE.REDEEM.EVENTS".equals(f.path()));
    }
}
