package io.testseer.backend.query;

import io.testseer.backend.api.TestSeerExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CatalogQueryController.class)
@Import(TestSeerExceptionHandler.class)
class CatalogQueryControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean CatalogQueryService catalogQueryService;
    @MockBean FreshnessResolver freshnessResolver;

    @Test
    void getDataObjects_returns404WhenNotIndexed() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.NOT_INDEXED);

        mockMvc.perform(get("/v1/catalog/data-objects").param("serviceId", "svc-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.freshnessStatus").value("NOT_INDEXED"));
    }

    @Test
    void getDataObjects_returnsPagedCatalogRows() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.CURRENT);
        when(catalogQueryService.queryDataObjects(eq("svc-1"), eq("MARIADB"), isNull(), eq(50), eq(0)))
                .thenReturn(PageResult.of(
                        List.of(new CatalogQueryService.DataObjectView(
                                "com.example.FooEntity", "com.example.domain.Foo",
                                "MARIADB", "Foo", null, "TABLE", "ENTITY_ANNOTATION", 0.95, null)),
                        120,
                        50,
                        0));

        mockMvc.perform(get("/v1/catalog/data-objects")
                        .param("serviceId", "svc-1")
                        .param("storeType", "MARIADB"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freshnessStatus").value("CURRENT"))
                .andExpect(jsonPath("$.data.items[0].physicalName").value("Foo"))
                .andExpect(jsonPath("$.data.total").value(120))
                .andExpect(jsonPath("$.data.limit").value(50))
                .andExpect(jsonPath("$.data.offset").value(0))
                .andExpect(jsonPath("$.data.hasMore").value(true));
    }

    @Test
    void getDataObjects_passesLimitAndOffset() throws Exception {
        when(freshnessResolver.resolve(anyString(), anyInt())).thenReturn(FreshnessStatus.CURRENT);
        when(catalogQueryService.queryDataObjects(eq("svc-1"), isNull(), isNull(), eq(10), eq(20)))
                .thenReturn(PageResult.of(List.of(), 0, 10, 20));

        mockMvc.perform(get("/v1/catalog/data-objects")
                        .param("serviceId", "svc-1")
                        .param("limit", "10")
                        .param("offset", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.limit").value(10))
                .andExpect(jsonPath("$.data.offset").value(20));
    }

    @Test
    void getDataObjects_rejectsInvalidLimit() throws Exception {
        mockMvc.perform(get("/v1/catalog/data-objects")
                        .param("serviceId", "svc-1")
                        .param("limit", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }
}
