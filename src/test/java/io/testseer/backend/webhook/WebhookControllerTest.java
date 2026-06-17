package io.testseer.backend.webhook;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WebhookController.class)
class WebhookControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean GitHubSignatureValidator validator;
    @MockBean JobDecomposer decomposer;
    @MockBean KafkaJobPublisher publisher;
    @MockBean io.testseer.backend.observability.TestSeerMetrics metrics;
    @MockBean io.testseer.backend.ingestion.GitHubSourceFetcher sourceFetcher;
    @MockBean WebhookCatalogRegistrar catalogRegistrar;

    private static final String PULL_REQUEST_PAYLOAD = """
            {
              "action": "synchronize",
              "number": 42,
              "pull_request": {
                "head": { "sha": "abc123" }
              },
              "repository": {
                "full_name": "acme/order-service",
                "owner": { "login": "acme" },
                "name": "order-service"
              },
              "installation": {}
            }
            """;

    @Test
    void pullRequest_withValidSignature_returns202() throws Exception {
        when(validator.isValid(any(), any())).thenReturn(true);
        when(sourceFetcher.fetchPullRequestChangedFiles(any(), any(), anyInt())).thenReturn(List.of());
        when(decomposer.decompose(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        mockMvc.perform(post("/webhook/github")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-Hub-Signature-256", "sha256=fakehash")
                        .contentType("application/json")
                        .content(PULL_REQUEST_PAYLOAD))
                .andExpect(status().isAccepted());
    }

    @Test
    void invalidSignature_returns401() throws Exception {
        when(validator.isValid(any(), any())).thenReturn(false);

        mockMvc.perform(post("/webhook/github")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-Hub-Signature-256", "sha256=badsig")
                        .contentType("application/json")
                        .content(PULL_REQUEST_PAYLOAD))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownEvent_returns200_silently() throws Exception {
        when(validator.isValid(any(), any())).thenReturn(true);

        mockMvc.perform(post("/webhook/github")
                        .header("X-GitHub-Event", "star")
                        .header("X-Hub-Signature-256", "sha256=fakehash")
                        .contentType("application/json")
                        .content("{\"action\":\"created\"}"))
                .andExpect(status().isOk());
    }
}
