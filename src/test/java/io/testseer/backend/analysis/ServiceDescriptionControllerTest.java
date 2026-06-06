package io.testseer.backend.analysis;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ServiceDescriptionController.class)
@ContextConfiguration(classes = {
        ServiceDescriptionController.class,
        ServiceDescriptionControllerTest.Config.class
})
class ServiceDescriptionControllerTest {

    @Autowired MockMvc mockMvc;

    @Autowired ServiceDescriptionService descriptionServiceBean;

    @TestConfiguration
    static class Config {
        static ServiceDescriptionService mockSvc = mock(ServiceDescriptionService.class);

        @Bean
        ServiceDescriptionService serviceDescriptionService() {
            return mockSvc;
        }

        @Bean
        Optional<ServiceDescriptionService> optionalDescriptionService(ServiceDescriptionService svc) {
            return Optional.of(svc);
        }
    }

    @Test
    void get_returns503_whenDisabled() throws Exception {
        // When the Optional is empty (simulated by creating a separate controller context),
        // the controller returns 503. We test this path by verifying the disabled case directly.
        // Since our test config provides a present Optional, we test the disabled path
        // via a different approach: build a standalone controller with empty Optional.
        ServiceDescriptionController ctrl =
                new ServiceDescriptionController(Optional.empty());

        // Invoke directly to verify the disabled-optional path
        var response = ctrl.getDescription("svc-001");
        assert response.getStatusCode().value() == 503;
    }

    @Test
    void get_returns404_whenNotGenerated() throws Exception {
        reset(Config.mockSvc);
        when(Config.mockSvc.getStored("svc-001")).thenReturn(null);

        mockMvc.perform(get("/v1/services/svc-001/description"))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_returns200_withStoredDescription() throws Exception {
        reset(Config.mockSvc);
        when(Config.mockSvc.getStored("svc-001"))
                .thenReturn("Manages order lifecycle including payment and fulfillment.");

        mockMvc.perform(get("/v1/services/svc-001/description"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("order lifecycle")));
    }

    @Test
    void post_returns503_whenDisabled() throws Exception {
        // Verify disabled-optional path directly via controller instantiation
        ServiceDescriptionController ctrl =
                new ServiceDescriptionController(Optional.empty());

        var response = ctrl.generateDescription("svc-001");
        assert response.getStatusCode().value() == 503;
    }
}
