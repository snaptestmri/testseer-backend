package io.testseer.backend.observability;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DlqDepthScheduler {

    private final TestSeerMetrics metrics;

    public DlqDepthScheduler(TestSeerMetrics metrics) {
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${testseer.observability.dlq-refresh-ms:60000}")
    public void refreshDlqDepth() {
        metrics.refreshDlqDepth();
    }
}
