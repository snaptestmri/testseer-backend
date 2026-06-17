package io.testseer.backend.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IndexingPhaseTimerTest {

    @Test
    void recordsLapDurationsAndFormatsSummary() throws InterruptedException {
        IndexingPhaseTimer timer = new IndexingPhaseTimer();
        Thread.sleep(5);
        timer.lap("phaseA");
        Thread.sleep(5);
        timer.lap("phaseB");

        assertThat(timer.phases()).containsKeys("phaseA", "phaseB");
        assertThat(timer.phases().get("phaseA")).isGreaterThanOrEqualTo(4L);
        assertThat(timer.elapsedMs()).isGreaterThanOrEqualTo(timer.phases().get("phaseA"));
        assertThat(timer.formatPhases()).contains("phaseA=", "phaseB=", "ms");
    }
}
