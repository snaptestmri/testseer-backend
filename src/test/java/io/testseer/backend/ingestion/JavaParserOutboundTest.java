package io.testseer.backend.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaParserOutboundTest {

    private final JavaParserService parser = new JavaParserService();

    @Test
    void restClient_getUri_extractsGetWithPath() {
        String source = """
                package com.example;
                import org.springframework.web.client.RestClient;
                public class PaymentClient {
                    private final RestClient restClient;
                    public PaymentClient(RestClient restClient) { this.restClient = restClient; }
                    public String fetch(String id) {
                        return restClient.get().uri("/payments/" + id).retrieve().body(String.class);
                    }
                }
                """;

        ParsedModel model = parser.parse("PaymentClient.java", source);

        List<ParsedModel.OutboundCallDef> calls = model.outboundCalls().stream()
                .filter(c -> c.httpMethod() != null)
                .toList();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).httpMethod()).isEqualTo("GET");
        assertThat(calls.get(0).path()).contains("/payments/");
        assertThat(calls.get(0).sourceMethod()).contains("fetch");
    }

    @Test
    void restClient_postUri_extractsPostWithPath() {
        String source = """
                package com.example;
                import org.springframework.web.client.RestClient;
                public class OrderClient {
                    private final RestClient restClient;
                    public void create() {
                        restClient.post().uri("/orders").retrieve().toBodilessEntity();
                    }
                }
                """;

        ParsedModel model = parser.parse("OrderClient.java", source);

        List<ParsedModel.OutboundCallDef> calls = model.outboundCalls().stream()
                .filter(c -> c.httpMethod() != null)
                .toList();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).httpMethod()).isEqualTo("POST");
        assertThat(calls.get(0).path()).isEqualTo("/orders");
    }

    @Test
    void restTemplate_getForEntity_extractsGetWithPath() {
        String source = """
                package com.example;
                import org.springframework.web.client.RestTemplate;
                public class InventoryClient {
                    private final RestTemplate restTemplate;
                    public void check() {
                        restTemplate.getForEntity("/inventory/items", String.class);
                    }
                }
                """;

        ParsedModel model = parser.parse("InventoryClient.java", source);

        List<ParsedModel.OutboundCallDef> calls = model.outboundCalls().stream()
                .filter(c -> c.httpMethod() != null)
                .toList();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).httpMethod()).isEqualTo("GET");
        assertThat(calls.get(0).path()).isEqualTo("/inventory/items");
        assertThat(calls.get(0).clientType()).isEqualTo("RestTemplate");
    }

    @Test
    void restTemplate_postForEntity_extractsPost() {
        String source = """
                package com.example;
                import org.springframework.web.client.RestTemplate;
                public class ShipmentClient {
                    private final RestTemplate template;
                    public void ship() {
                        template.postForEntity("/shipments", null, Void.class);
                    }
                }
                """;

        ParsedModel model = parser.parse("ShipmentClient.java", source);

        List<ParsedModel.OutboundCallDef> calls = model.outboundCalls().stream()
                .filter(c -> c.httpMethod() != null)
                .toList();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).httpMethod()).isEqualTo("POST");
        assertThat(calls.get(0).path()).isEqualTo("/shipments");
    }

    @Test
    void feignClient_extractsMethodAnnotationsAsOutboundCalls() {
        String source = """
                package com.example;
                import org.springframework.cloud.openfeign.FeignClient;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PostMapping;
                @FeignClient(name = "payment-service")
                public interface PaymentServiceClient {
                    @GetMapping("/payments/{id}")
                    String getPayment(String id);
                    @PostMapping("/payments")
                    String createPayment(String body);
                }
                """;

        ParsedModel model = parser.parse("PaymentServiceClient.java", source);

        List<ParsedModel.OutboundCallDef> calls = model.outboundCalls().stream()
                .filter(c -> c.httpMethod() != null)
                .toList();
        assertThat(calls).hasSize(2);
        assertThat(calls).anyMatch(c -> "GET".equals(c.httpMethod()) && "/payments/{id}".equals(c.path()));
        assertThat(calls).anyMatch(c -> "POST".equals(c.httpMethod()) && "/payments".equals(c.path()));
        assertThat(calls).allMatch(c -> "FeignClient".equals(c.clientType()));
    }

    @Test
    void noHttpCalls_returnsFieldLevelFallback() {
        String source = """
                package com.example;
                import org.springframework.web.client.RestClient;
                public class AmbiguousClient {
                    private final RestClient client;
                    public void doSomething(String uri) {
                        client.get().uri(uri).retrieve().body(String.class);
                    }
                }
                """;

        ParsedModel model = parser.parse("AmbiguousClient.java", source);

        assertThat(model.outboundCalls().stream()
                .anyMatch(c -> c.clientType().contains("RestClient"))).isTrue();
    }

    @Test
    void restTemplate_exchange_extractsPostWithDynamicUri() {
        String source = """
                package com.example;
                import org.springframework.http.HttpMethod;
                import org.springframework.web.client.RestTemplate;
                public class HyveeRestClient {
                    private final RestTemplate restTemplate;
                    public String send(String requestURI) {
                        return restTemplate.exchange(requestURI, HttpMethod.POST, null, String.class).getBody();
                    }
                }
                """;

        ParsedModel model = parser.parse("HyveeRestClient.java", source);

        List<ParsedModel.OutboundCallDef> calls = model.outboundCalls().stream()
                .filter(c -> c.httpMethod() != null)
                .toList();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).httpMethod()).isEqualTo("POST");
        assertThat(calls.get(0).path()).isNull();
    }

    @Test
    void restServiceSubclass_callWithRetry_extractsConfigBackedOutbound() {
        String source = """
                package com.example;
                import com.quotient.api.rest.RestService;
                import org.springframework.boot.context.properties.ConfigurationProperties;
                @ConfigurationProperties("rest.apis.pubsub")
                public class PubSubNotificationClient extends RestService<Void, Object> {
                    public void callNotificationAPI() {
                        callWithRetry(null, Void.class);
                    }
                }
                """;

        ParsedModel model = parser.parse(
                "evaluation-consumers/transaction-eval-consumer/src/main/java/com/example/PubSubNotificationClient.java",
                source);

        assertThat(model.outboundCalls()).anyMatch(c ->
                "RestService".equals(c.clientType())
                        && "POST".equals(c.httpMethod())
                        && "rest.apis.pubsub".equals(c.path()));
    }

    @Test
    void workbenchClient_createWorkbenchSubmission_extractsOutbound() {
        String source = """
                package com.example;
                import com.quotient.platform.restclients.workbench.WorkbenchSubmissionRestClient;
                public class WorkbenchSubmissionRest {
                    private WorkbenchSubmissionRestClient workbenchSubmissionRestClient;
                    public void sendToWB(Object request, String partnerId) {
                        workbenchSubmissionRestClient.createWorkbenchSubmission(request, partnerId);
                    }
                }
                """;

        ParsedModel model = parser.parse("WorkbenchSubmissionRest.java", source);

        assertThat(model.outboundCalls()).anyMatch(c ->
                "POST".equals(c.httpMethod())
                        && c.clientType().toLowerCase().contains("workbench"));
    }
}
