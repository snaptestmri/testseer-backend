package io.testseer.backend.analysis;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import io.testseer.backend.api.TestSeerExceptionHandler;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ServiceDescriptionController.class)
@Import(TestSeerExceptionHandler.class)
@ContextConfiguration(classes = {
        ServiceDescriptionController.class,
        ServiceDescriptionControllerTest.Config.class
})
class ServiceDescriptionControllerTest {

    @Autowired MockMvc mockMvc;

    @TestConfiguration
    static class Config {
        static ServiceDescriptionService mockSvc = mock(ServiceDescriptionService.class);

        @Bean
        ServiceDescriptionService serviceDescriptionService() {
            return mockSvc;
        }
    }

    @Test
    void get_returns404_whenNotGenerated() throws Exception {
        reset(Config.mockSvc);
        when(Config.mockSvc.getStoredDetails("svc-001")).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/services/svc-001/description"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void get_returns200_withJsonDescription() throws Exception {
        reset(Config.mockSvc);
        Instant generatedAt = Instant.parse("2026-06-12T10:00:00Z");
        when(Config.mockSvc.getStoredDetails("svc-001"))
                .thenReturn(Optional.of(new ServiceDescriptionService.StoredDescription(
                        "Manages order lifecycle including payment and fulfillment.",
                        generatedAt,
                        "legacy-model")));

        mockMvc.perform(get("/v1/services/svc-001/description"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceId").value("svc-001"))
                .andExpect(jsonPath("$.description").value(
                        "Manages order lifecycle including payment and fulfillment."))
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.model").value("legacy-model"));
    }
}
