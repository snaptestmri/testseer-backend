package io.testseer.backend.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

class IndexCompleteNotifierTest {

    @Test
    void notifyComplete_publishesToHubAndInvalidatesCache() {
        CacheService cache = Mockito.mock(CacheService.class);
        IndexNotificationHub hub = new IndexNotificationHub();
        IndexCompleteNotifier notifier = new IndexCompleteNotifier(
                null, cache, new ObjectMapper(), hub, false, true);

        notifier.notifyComplete("quotient", "repo-a", "svc-1", "abc123", "job-1");

        verify(cache).invalidate("quotient", "repo-a", "svc-1");
        List<IndexCompleteEvent> events = hub.poll("svc-1", Instant.EPOCH, 10);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).eventType()).isEqualTo(IndexCompleteEvent.TYPE_COMPLETE);
        assertThat(events.get(0).commitSha()).isEqualTo("abc123");
    }
}
