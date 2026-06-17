package io.testseer.backend.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.api.TestSeerExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ServiceRegistryController.class)
@Import(TestSeerExceptionHandler.class)
class ServiceRegistryControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper mapper;

    @MockBean
    ServiceRegistryService service;

    private static ServiceEntry sampleEntry() {
        return new ServiceEntry(
                "svc-001", "acme", "order-service", "orders",
                "service", "MAVEN",
                List.of("src/main/java"), List.of("src/test/java"),
                "platform", true, Instant.now(), Instant.now()
        );
    }

    @Test
    void POST_registry_returns201_withLocation() throws Exception {
        when(service.register(any())).thenReturn(sampleEntry());

        mockMvc.perform(post("/registry/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"orgId":"acme","repo":"order-service",
                             "serviceName":"orders","buildTool":"MAVEN"}
                            """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/registry/services/svc-001"))
                .andExpect(jsonPath("$.serviceId").value("svc-001"));
    }

    @Test
    void POST_registry_returns400_whenRequiredFieldMissing() throws Exception {
        mockMvc.perform(post("/registry/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"orgId":"acme","repo":"order-service"}
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void POST_registry_returns409_whenDuplicate() throws Exception {
        when(service.register(any()))
                .thenThrow(new DuplicateServiceException("acme", "order-service", "orders"));

        mockMvc.perform(post("/registry/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"orgId":"acme","repo":"order-service",
                             "serviceName":"orders","buildTool":"MAVEN"}
                            """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void GET_registry_byId_returns404_whenNotFound() throws Exception {
        when(service.getById("missing"))
                .thenThrow(new ServiceNotFoundException("missing"));

        mockMvc.perform(get("/registry/services/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void PATCH_registry_disables_service() throws Exception {
        when(service.update(any(), any())).thenReturn(sampleEntry());

        mockMvc.perform(patch("/registry/services/svc-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceId").value("svc-001"));
    }
}
