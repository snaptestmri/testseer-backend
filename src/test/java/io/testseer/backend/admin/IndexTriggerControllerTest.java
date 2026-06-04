package io.testseer.backend.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.registry.ServiceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(IndexTriggerController.class)
class IndexTriggerControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean IndexTriggerService triggerService;

    @Test
    void trigger_returns202_withJobDetails() throws Exception {
        when(triggerService.trigger(eq("svc-001"), any()))
                .thenReturn(new IndexTriggerResponse("job-123", "svc-001", "abc123", 42));

        mockMvc.perform(post("/admin/index/svc-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commitSha\":\"abc123\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-123"))
                .andExpect(jsonPath("$.commitSha").value("abc123"))
                .andExpect(jsonPath("$.fileCount").value(42));
    }

    @Test
    void trigger_withNoBody_returns202() throws Exception {
        when(triggerService.trigger(eq("svc-001"), any()))
                .thenReturn(new IndexTriggerResponse("job-456", "svc-001", "headsha", 10));

        mockMvc.perform(post("/admin/index/svc-001"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-456"));
    }

    @Test
    void trigger_returns404_whenServiceNotFound() throws Exception {
        when(triggerService.trigger(eq("svc-unknown"), any()))
                .thenThrow(new ServiceNotFoundException("svc-unknown"));

        mockMvc.perform(post("/admin/index/svc-unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void trigger_returns409_whenJobAlreadyInFlight() throws Exception {
        when(triggerService.trigger(eq("svc-001"), any()))
                .thenThrow(new JobAlreadyInFlightException("svc-001"));

        mockMvc.perform(post("/admin/index/svc-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());
    }
}
