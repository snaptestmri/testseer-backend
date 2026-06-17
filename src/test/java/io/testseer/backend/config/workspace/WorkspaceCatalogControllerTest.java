package io.testseer.backend.config.workspace;

import io.testseer.backend.api.TestSeerExceptionHandler;
import io.testseer.backend.config.WorkspaceConfig;
import io.testseer.backend.registry.ServiceEntry;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WorkspaceCatalogController.class)
@Import(TestSeerExceptionHandler.class)
class WorkspaceCatalogControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean WorkspaceCatalogAdminService adminService;

    private static WorkspaceConfig.CatalogLibraryConfig sampleLibrary() {
        return new WorkspaceConfig.CatalogLibraryConfig(
                "platform-data",
                "optimus-platform-framework",
                "platform-data",
                List.of("platform-data/src/main/java"),
                false,
                false
        );
    }

    @Test
    void POST_catalogLibrary_returns201() throws Exception {
        when(adminService.createCatalogLibrary(eq("quotient"), any()))
                .thenReturn(sampleLibrary());

        mockMvc.perform(post("/v1/workspace/catalog-libraries")
                        .param("orgId", "quotient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": "platform-data",
                                  "repo": "optimus-platform-framework",
                                  "sourceRoots": ["platform-data/src/main/java"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("platform-data"));
    }

    @Test
    void GET_catalogLibrary_returns404_whenMissing() throws Exception {
        when(adminService.getCatalogLibrary("quotient", "missing"))
                .thenThrow(new CatalogLibraryNotFoundException("quotient", "missing"));

        mockMvc.perform(get("/v1/workspace/catalog-libraries/missing")
                        .param("orgId", "quotient"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void POST_registerCatalogLibrary_returnsServiceEntry() throws Exception {
        when(adminService.registerCatalogLibrary("quotient", "platform-data", "MAVEN"))
                .thenReturn(new ServiceEntry(
                        "svc-1", "quotient", "optimus-platform-framework", "platform-data",
                        "library", "MAVEN", List.of("platform-data/src/main/java"),
                        List.of("src/test/java"), null, true, Instant.now(), Instant.now()));

        mockMvc.perform(post("/v1/workspace/catalog-libraries/platform-data/register")
                        .param("orgId", "quotient")
                        .param("buildTool", "MAVEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moduleType").value("library"));
    }
}
