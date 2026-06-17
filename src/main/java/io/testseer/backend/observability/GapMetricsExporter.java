package io.testseer.backend.observability;

import io.testseer.backend.config.WorkspaceCatalogService;
import io.testseer.backend.config.WorkspaceConfig;
import io.testseer.backend.query.DataObjectGapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Periodically queries gap counts and exports them as Micrometer gauges via
 * {@link TestSeerMetrics#setGapCount(String, long)}.
 *
 * <p>Exposed metrics:
 * <ul>
 *   <li>{@code testseer.gaps.count{gap_type="LIBRARY_NOT_INDEXED"}} — D4 Grafana panel
 *   <li>{@code testseer.gaps.count{gap_type="LIBRARY_STALE"}}
 *   <li>{@code testseer.gaps.count{gap_type="HANDLER_WITHOUT_CATALOG"}}
 *   <li>{@code testseer.gaps.count{gap_type="INFERRED_NOT_IN_DDL"}}
 *   <li>{@code testseer.gaps.count{gap_type="DDL_UNREFERENCED"}}
 * </ul>
 *
 * <p>Runs every 5 minutes by default. Configure via
 * {@code testseer.observability.gap-export-cron} if needed.
 */
@Component
public class GapMetricsExporter {

    private static final Logger log = LoggerFactory.getLogger(GapMetricsExporter.class);

    private final DataObjectGapService gapService;
    private final WorkspaceCatalogService workspaceCatalog;
    private final TestSeerMetrics metrics;

    public GapMetricsExporter(DataObjectGapService gapService,
                               WorkspaceCatalogService workspaceCatalog,
                               TestSeerMetrics metrics) {
        this.gapService = gapService;
        this.workspaceCatalog = workspaceCatalog;
        this.metrics = metrics;
    }

    @Scheduled(fixedRateString = "${testseer.observability.gap-export-interval-ms:300000}")
    public void exportGapCounts() {
        WorkspaceConfig cfg = workspaceCatalog.config();
        String orgId = cfg.defaultOrgId();
        if (orgId == null || orgId.isBlank()) {
            return;
        }
        try {
            List<DataObjectGapService.DataObjectGapView> gaps = gapService.computeGaps(orgId);
            Map<String, Long> countsByType = gaps.stream()
                    .collect(Collectors.groupingBy(
                            DataObjectGapService.DataObjectGapView::gapType,
                            Collectors.counting()));
            countsByType.forEach(metrics::setGapCount);
            log.debug("Gap metrics exported for org={}: {}", orgId, countsByType);
        } catch (Exception ex) {
            log.warn("GapMetricsExporter failed for org={}: {}", orgId, ex.getMessage());
        }
    }
}
