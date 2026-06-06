package io.testseer.backend.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DiscoveryController.class)
class DiscoveryControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean DiscoveryService discoveryService;

    @Test
    void discover_returns200_withCounts() throws Exception {
        when(discoveryService.discover("acme")).thenReturn(
                new DiscoveryResult(
                        List.of("orders", "payments"),
                        List.of("billing"),
                        List.of("frontend")
                ));

        mockMvc.perform(post("/admin/discover").param("orgId", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered.length()").value(2))
                .andExpect(jsonPath("$.alreadyKnown[0]").value("billing"))
                .andExpect(jsonPath("$.skipped[0]").value("frontend"));
    }

    @Test
    void discover_missingOrgId_returns400() throws Exception {
        mockMvc.perform(post("/admin/discover"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void discover_emptyOrg_returns200_withEmptyLists() throws Exception {
        when(discoveryService.discover("empty-org")).thenReturn(
                new DiscoveryResult(List.of(), List.of(), List.of()));

        mockMvc.perform(post("/admin/discover").param("orgId", "empty-org"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered.length()").value(0));
    }
}
