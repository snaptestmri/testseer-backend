package io.testseer.backend.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LocalIndexTriggerController.class)
class LocalIndexTriggerControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean LocalIndexTriggerService triggerService;

    @Test
    void trigger_returns200_withIndexResult() throws Exception {
        when(triggerService.trigger(any())).thenReturn(
                new LocalIndexTriggerResponse("svc-001", "orders", "abc123", 42, true));

        mockMvc.perform(post("/admin/index/local")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":\"acme\",\"path\":\"/workspace/orders\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceId").value("svc-001"))
                .andExpect(jsonPath("$.serviceName").value("orders"))
                .andExpect(jsonPath("$.fileCount").value(42))
                .andExpect(jsonPath("$.autoRegistered").value(true));
    }

    @Test
    void trigger_returns400_whenPathInvalid() throws Exception {
        when(triggerService.trigger(any()))
                .thenThrow(new IllegalArgumentException("Path is not a directory: /bad"));

        mockMvc.perform(post("/admin/index/local")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":\"acme\",\"path\":\"/bad\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("not a directory")));
    }

    @Test
    void trigger_returns400_whenBodyMissingRequiredFields() throws Exception {
        mockMvc.perform(post("/admin/index/local")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":\"\",\"path\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
