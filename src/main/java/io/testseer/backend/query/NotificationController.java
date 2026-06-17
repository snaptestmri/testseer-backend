package io.testseer.backend.query;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;

@Tag(name = "Notifications", description = "Index-complete push for IDE clients (BL-024)")
@RestController
@RequestMapping("/v1/notifications")
public class NotificationController {

    private final IndexNotificationHub hub;

    public NotificationController(IndexNotificationHub hub) {
        this.hub = hub;
    }

    @Operation(summary = "SSE stream of index-complete events")
    @GetMapping(value = "/index-events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter indexEvents(@RequestParam(required = false) String serviceId) {
        return hub.subscribe(serviceId);
    }

    @Operation(summary = "Long-poll fallback for index-complete events")
    @GetMapping("/index-events/poll")
    public ResponseEntity<ResponseEnvelope<List<IndexCompleteEvent>>> pollIndexEvents(
            @RequestParam(required = false) String serviceId,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "50") int limit) {

        Instant afterInstant = after != null && !after.isBlank() ? Instant.parse(after) : null;
        List<IndexCompleteEvent> events = hub.poll(serviceId, afterInstant, limit);
        return ResponseEntity.ok(ResponseEnvelope.of(
                Instant.now(), null, FreshnessStatus.CURRENT, events));
    }
}
