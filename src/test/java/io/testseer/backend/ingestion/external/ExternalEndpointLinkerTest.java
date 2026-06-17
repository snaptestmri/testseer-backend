package io.testseer.backend.ingestion.external;

import io.testseer.backend.config.MessagingRulePackLoader;
import io.testseer.backend.ingestion.ParsedModel;
import io.testseer.backend.ingestion.messaging.MessagingFactOrchestrator;
import io.testseer.backend.ingestion.messaging.YamlPubSubExtractor;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalEndpointLinkerTest {

    private final ExternalEndpointLinker linker = new ExternalEndpointLinker(
            new MessagingRulePackLoader(
                    new FileSystemResource("../config/rule-packs/quotient-messaging.yml")),
            new YamlExternalEndpointExtractor(),
            new ExternalCallSiteExtractor()
    );

    @Test
    void link_hyveeAdapterYamlAndJavaCallSite() {
        String yaml = """
                integrator:
                  partners:
                    hyvee:
                      offer-endpoint: https://dlpweb.hy-vee.com/LoyaltyOnlineWS/REST/Promotion.ashx
                """;

        String adapterSource = """
                package com.example;
                import org.springframework.web.client.RestTemplate;
                public class HyveeOfferAdapter {
                    private HyveePartnerIntegratorConfig hyveeConfigs;
                    private HyveeRestClient hyveeRestClient;
                    protected String getRequestURI(Offer offer) {
                        return hyveeConfigs.getOfferEndpoint();
                    }
                    protected void callHyveeAPIWithAuthRetry() {
                        String partnerRequestUri = getRequestURI(null);
                        hyveeRestClient.sendOfferSyncRequest("key", partnerRequestUri, "{}", "1");
                    }
                }
                """;

        String clientSource = """
                package com.example;
                import org.springframework.http.HttpMethod;
                import org.springframework.web.client.RestTemplate;
                public class HyveeRestClient {
                    private RestTemplate restTemplate;
                    public String sendOfferSyncRequest(String sessionKey, String requestURI,
                            String requestBody, String offerId) {
                        return restTemplate.exchange(requestURI, HttpMethod.POST, null, String.class).getBody();
                    }
                }
                """;

        List<MessagingFactOrchestrator.SourceFile> sources = List.of(
                source("HyveeOfferAdapter.java", adapterSource, "com.example.HyveeOfferAdapter"),
                source("HyveeRestClient.java", clientSource, "com.example.HyveeRestClient")
        );

        List<ParsedModel> models = sources.stream().map(MessagingFactOrchestrator.SourceFile::parsedModel).toList();
        List<YamlPubSubExtractor.ConfigFile> configs =
                List.of(new YamlPubSubExtractor.ConfigFile("application-pdn.yaml", yaml));

        ExternalEndpointLinker.LinkedExternalFacts linked =
                linker.link(sources, models, configs, "pdn");

        assertThat(linked.endpoints()).anyMatch(e ->
                "hyvee:offer_sync".equals(e.endpointId())
                        && "POST".equals(e.httpMethod())
                        && e.urlResolved().contains("Promotion.ashx"));

        assertThat(linked.callSites()).isNotEmpty();
        assertThat(linked.hints()).anyMatch(h -> h.hintKind().equals("EXTERNAL_ENDPOINT"));
    }

    private static MessagingFactOrchestrator.SourceFile source(
            String path, String content, String fqn) {
        return new MessagingFactOrchestrator.SourceFile(
                path, content,
                ParsedModel.of(path, fqn, List.of(), List.of(), List.of(),
                        List.of(), List.of(), false, null, null, List.of(), List.of())
        );
    }
}
