package io.testseer.backend.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.api.TestSeerExceptionHandler;
import io.testseer.backend.ingestion.maven.MavenLinkBackfillService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MavenLinkBackfillController.class)
@Import(TestSeerExceptionHandler.class)
class MavenLinkBackfillControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean MavenLinkBackfillService backfillService;

    @Test
    void backfillLinks_returnsSummary() throws Exception {
        when(backfillService.backfill(any())).thenReturn(new MavenLinkBackfillResponse(
                "quotient", 1, 3, 2,
                List.of(new MavenLinkBackfillResponse.ServiceBackfillSummary("svc-1", "sha-1", 3, 2))));

        mockMvc.perform(post("/admin/maven/backfill-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":\"quotient\",\"serviceId\":\"svc-1\",\"syncOwnedByEdges\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orgId").value("quotient"))
                .andExpect(jsonPath("$.dependencyRowsUpdated").value(3))
                .andExpect(jsonPath("$.ownedByEdgesSynced").value(2));

        verify(backfillService).backfill(any());
    }
}
