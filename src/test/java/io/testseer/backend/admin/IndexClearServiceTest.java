package io.testseer.backend.admin;

import io.testseer.backend.query.CacheService;
import io.testseer.backend.query.IndexCompleteNotifier;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IndexClearServiceTest {

    @Mock JdbcClient db;
    @Mock JdbcClient.StatementSpec spec;
    @Mock MongoTemplate mongo;
    @Mock ServiceRegistryService registryService;
    @Mock io.testseer.backend.graph.GraphNodeRepository nodeRepo;
    @Mock io.testseer.backend.graph.GraphEdgeRepository edgeRepo;
    @Mock CacheService cacheService;
    @Mock IndexCompleteNotifier indexCompleteNotifier;

    @InjectMocks IndexClearService service;

    private ServiceEntry entry() {
        return new ServiceEntry("svc-1", "quotient", "orders", "orders",
                "service", "MAVEN", List.of(), List.of(), null, true, null, null);
    }

    @Test
    void clearService_deletesFactsAndInvalidatesCache() {
        when(registryService.getById("svc-1")).thenReturn(entry());
        when(db.sql(anyString())).thenReturn(spec);
        when(spec.param(anyString(), any())).thenReturn(spec);
        when(spec.update()).thenReturn(1);
        when(mongo.remove(any(Query.class), eq("parsed_models"))).thenReturn(
                com.mongodb.client.result.DeleteResult.acknowledged(1));

        IndexClearResponse resp = service.clearService("svc-1");

        assertThat(resp.scope()).isEqualTo("SERVICE");
        assertThat(resp.serviceId()).isEqualTo("svc-1");
        assertThat(resp.totalDeleted()).isGreaterThan(0);
        verify(cacheService).invalidate("quotient", "orders", "svc-1");
        verify(indexCompleteNotifier).notifyCleared("quotient", "orders", "svc-1");
        verify(nodeRepo).deleteByServiceIdOrName(eq("quotient"), eq("svc-1"), eq("orders"), eq("svc-1::%"));
    }

    @Test
    void clearMessaging_onlyDeletesOptionCFacts() {
        when(registryService.getById("svc-1")).thenReturn(entry());
        when(db.sql(anyString())).thenReturn(spec);
        when(spec.param(anyString(), any())).thenReturn(spec);
        when(spec.update()).thenReturn(2);

        IndexClearResponse resp = service.clearMessaging("svc-1");

        assertThat(resp.scope()).isEqualTo("MESSAGING");
        assertThat(resp.deletedCounts()).containsKey("pubsubResourceFacts");
        assertThat(resp.deletedCounts()).doesNotContainKey("symbolFacts");
    }

    @Test
    void clearOrg_invalidatesOrgCache() {
        when(db.sql(anyString())).thenReturn(spec);
        when(spec.param(anyString(), any())).thenReturn(spec);
        when(spec.update()).thenReturn(1);
        when(mongo.remove(any(Query.class), eq("parsed_models"))).thenReturn(
                com.mongodb.client.result.DeleteResult.acknowledged(1));

        IndexClearResponse resp = service.clearOrg("quotient", true);

        assertThat(resp.scope()).isEqualTo("ORG");
        verify(cacheService).invalidateOrg("quotient");
    }
}
