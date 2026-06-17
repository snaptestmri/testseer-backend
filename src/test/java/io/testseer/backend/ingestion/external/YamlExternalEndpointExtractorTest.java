package io.testseer.backend.ingestion.external;

import io.testseer.backend.ingestion.messaging.YamlPubSubExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class YamlExternalEndpointExtractorTest {

    private final YamlExternalEndpointExtractor extractor = new YamlExternalEndpointExtractor();

    @Test
    void extract_findsHyveeOfferEndpointFromDevYaml() {
        String yaml = """
                integrator:
                  partners:
                    hyvee:
                      offer-endpoint: http://riq-mock-api-gw/mockapi-service/LoyaltyOnlineWS/REST/Promotion.ashx
                ois-rest-template-configs:
                  partner-publish-details-endpoint: https://riq-dev.corp.quotient.com/ois/offer/%s/partnerpublishingdetails
                """;

        List<YamlExternalEndpointExtractor.YamlEndpointCandidate> results = extractor.extract(
                List.of(new YamlPubSubExtractor.ConfigFile(
                        "partner-adapter-consumer/src/main/resources/application-dev.yaml", yaml)));

        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results).anyMatch(r ->
                "integrator.partners.hyvee.offer-endpoint".equals(r.configKey())
                        && r.urlResolved().contains("Promotion.ashx")
                        && "dev".equals(r.envLane()));
        assertThat(results).anyMatch(r ->
                "ois-rest-template-configs.partner-publish-details-endpoint".equals(r.configKey())
                        && r.urlResolved().contains("riq-dev.corp.quotient.com")
                        && "dev".equals(r.envLane()));
    }

    @Test
    void extract_findsHyveeOfferEndpointFromPdnYaml() {
        String yaml = """
                integrator:
                  partners:
                    hyvee:
                      offer-endpoint: https://dlpweb.hy-vee.com/LoyaltyOnlineWS/REST/Promotion.ashx
                ois-rest-template-configs:
                  partner-publish-details-endpoint: https://riq-pdn.corp.quotient.com/ois/offer/%s/partnerpublishingdetails
                """;

        List<YamlExternalEndpointExtractor.YamlEndpointCandidate> results = extractor.extract(
                List.of(new YamlPubSubExtractor.ConfigFile("application-pdn.yaml", yaml)));

        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results).anyMatch(r ->
                "integrator.partners.hyvee.offer-endpoint".equals(r.configKey())
                        && r.urlResolved().contains("hy-vee.com")
                        && "EXTERNAL".equals(r.boundary())
                        && "pdn".equals(r.envLane()));
        assertThat(results).anyMatch(r ->
                "ois-rest-template-configs.partner-publish-details-endpoint".equals(r.configKey())
                        && "INTERNAL".equals(r.boundary()));
    }

    @Test
    void classifyBoundary_marksHyveeExternal() {
        assertThat(YamlExternalEndpointExtractor.classifyBoundary(
                "https://dlpweb.hy-vee.com/LoyaltyOnlineWS/REST/Promotion.ashx"))
                .isEqualTo("EXTERNAL");
        assertThat(YamlExternalEndpointExtractor.classifyBoundary(
                "https://riq-pdn.corp.quotient.com/ois/offer/%s/partnerpublishingdetails"))
                .isEqualTo("INTERNAL");
        assertThat(YamlExternalEndpointExtractor.classifyBoundary("/workbench/submission"))
                .isEqualTo("INTERNAL");
    }

    @Test
    void extract_unwrapsConfigMapEmbeddedApplicationYaml() {
        String configMap = """
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: transaction-eval-consumer
                data:
                  application.yaml: |-
                    spring:
                      profiles:
                        active: dev
                    rest:
                      apis:
                        pubsub:
                          uri: http://10.212.11.202:80/pubsub/service/publish
                          method: POST
                          topic-name: DEV_T.NOTIFICATION_REQ
                """;

        List<YamlExternalEndpointExtractor.YamlEndpointCandidate> results = extractor.extract(
                List.of(new YamlPubSubExtractor.ConfigFile(
                        "evaluation-consumers/transaction-eval-consumer/kubernetes-manifests/dev/transaction-eval-consumer.dev.config-map.yaml",
                        configMap)));

        assertThat(results).anyMatch(r ->
                "rest.apis.pubsub.uri".equals(r.configKey())
                        && r.urlResolved().contains("/pubsub/service/publish")
                        && "dev".equals(r.envLane())
                        && "DEV_T.NOTIFICATION_REQ".equals(r.topicName()));
    }

    @Test
    void extract_findsRestApisPubsubUriAndTopicName() {
        String yaml = """
                rest:
                  apis:
                    pubsub:
                      uri: http://10.212.11.202:80/pubsub/service/publish
                      method: POST
                      topic-name: DEV_T.NOTIFICATION_REQ
                """;

        List<YamlExternalEndpointExtractor.YamlEndpointCandidate> results = extractor.extract(
                List.of(new YamlPubSubExtractor.ConfigFile("application.yaml", yaml)));

        assertThat(results).anyMatch(r ->
                "rest.apis.pubsub.uri".equals(r.configKey())
                        && r.urlResolved().contains("/pubsub/service/publish")
                        && "POST".equals(r.httpMethod())
                        && "DEV_T.NOTIFICATION_REQ".equals(r.topicName()));
    }
}
