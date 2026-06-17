package io.testseer.backend.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.api.TestSeerExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(IndexClearController.class)
@Import(TestSeerExceptionHandler.class)
class IndexClearControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean IndexClearService indexClearService;

    @Test
    void clear_returnsDeletedCounts() throws Exception {
        when(indexClearService.clear(any())).thenReturn(new IndexClearResponse(
                "SERVICE", "quotient", "svc-1", "orders",
                Map.of("symbolFacts", 10, "pubsubResourceFacts", 5)));

        mockMvc.perform(post("/admin/index/clear")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"SERVICE\",\"serviceId\":\"svc-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("SERVICE"))
                .andExpect(jsonPath("$.deletedCounts.symbolFacts").value(10));
    }

    @Test
    void clearServiceById_delegatesToService() throws Exception {
        when(indexClearService.clear(any())).thenReturn(new IndexClearResponse(
                "SERVICE", "quotient", "svc-1", "orders", Map.of("graphEdges", 3)));

        mockMvc.perform(delete("/admin/index/svc-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceId").value("svc-1"));

        verify(indexClearService).clear(any());
    }

    @Test
    void clear_returns400_onBadRequest() throws Exception {
        when(indexClearService.clear(any()))
                .thenThrow(new IllegalArgumentException("serviceId is required"));

        mockMvc.perform(post("/admin/index/clear")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"SERVICE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }
}
