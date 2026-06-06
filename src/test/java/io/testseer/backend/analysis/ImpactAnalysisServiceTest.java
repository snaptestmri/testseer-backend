package io.testseer.backend.analysis;

import io.testseer.backend.graph.GraphProjectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImpactAnalysisServiceTest {

    @Mock JdbcClient db;
    @Mock GraphProjectionService graphService;

    ImpactAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new ImpactAnalysisService(db, new com.fasterxml.jackson.databind.ObjectMapper(), graphService);
    }

    @Test
    void buildReport_returnsEmptyLists_whenNoFactsForCommit() {
        stubDbReturningChanged(List.of());

        ImpactReport report = service.buildReport("svc-001", "abc123");

        assertThat(report.serviceId()).isEqualTo("svc-001");
        assertThat(report.commitSha()).isEqualTo("abc123");
        assertThat(report.changedSymbols()).isEmpty();
        assertThat(report.affectedConsumers()).isEmpty();
        assertThat(report.downstreamDependencies()).isEmpty();
        assertThat(report.suggestedTestScope()).isEmpty();
    }

    private void stubDbReturningChanged(List<ImpactReport.ChangedSymbol> changed) {
        when(db.sql(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0, String.class);
            JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class, RETURNS_DEEP_STUBS);
            if (sql.contains("WHERE service_id = :svcId AND commit_sha = :sha")) {
                when(spec.param(anyString(), any()).query(any(RowMapper.class)).list())
                        .thenReturn(changed);
            } else {
                when(spec.param(anyString(), any()).query(any(RowMapper.class)).list())
                        .thenReturn(List.of());
                when(spec.param(anyString(), any()).query(String.class).list())
                        .thenReturn(List.of());
                when(spec.param(anyString(), any()).query(String.class).optional())
                        .thenReturn(Optional.empty());
            }
            return spec;
        });
    }
}
