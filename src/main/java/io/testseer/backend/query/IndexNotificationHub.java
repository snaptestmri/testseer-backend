package io.testseer.backend.query;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class IndexNotificationHub {

    private final CopyOnWriteArrayList<SseEmitter> broadcastEmitters = new CopyOnWriteArrayList<>();
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emittersByService = new ConcurrentHashMap<>();
    private final List<IndexCompleteEvent> recentEvents = new CopyOnWriteArrayList<>();

    private static final int MAX_RECENT = 200;

    public void publish(IndexCompleteEvent event) {
        recentEvents.add(event);
        trimRecent();
        for (SseEmitter emitter : broadcastEmitters) {
            send(emitter, event);
        }
        if (event.serviceId() != null) {
            CopyOnWriteArrayList<SseEmitter> serviceEmitters = emittersByService.get(event.serviceId());
            if (serviceEmitters != null) {
                for (SseEmitter emitter : serviceEmitters) {
                    send(emitter, event);
                }
            }
        }
    }

    public SseEmitter subscribe(String serviceId) {
        SseEmitter emitter = new SseEmitter(0L);
        if (serviceId == null || serviceId.isBlank()) {
            broadcastEmitters.add(emitter);
        } else {
            emittersByService.computeIfAbsent(serviceId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        }
        emitter.onCompletion(() -> remove(emitter, serviceId));
        emitter.onTimeout(() -> remove(emitter, serviceId));
        emitter.onError(ex -> remove(emitter, serviceId));
        return emitter;
    }

    public List<IndexCompleteEvent> poll(String serviceId, Instant after, int limit) {
        List<IndexCompleteEvent> filtered = new ArrayList<>();
        for (IndexCompleteEvent event : recentEvents) {
            if (after != null && !event.indexedAt().isAfter(after)) continue;
            if (serviceId != null && !serviceId.isBlank() && !serviceId.equals(event.serviceId())) continue;
            filtered.add(event);
        }
        if (filtered.size() <= limit) return filtered;
        return filtered.subList(filtered.size() - limit, filtered.size());
    }

    private void send(SseEmitter emitter, IndexCompleteEvent event) {
        try {
            emitter.send(SseEmitter.event().name("index-event").data(event));
        } catch (IOException ex) {
            emitter.completeWithError(ex);
        }
    }

    private void remove(SseEmitter emitter, String serviceId) {
        if (serviceId == null || serviceId.isBlank()) {
            broadcastEmitters.remove(emitter);
        } else {
            CopyOnWriteArrayList<SseEmitter> list = emittersByService.get(serviceId);
            if (list != null) {
                list.remove(emitter);
            }
        }
    }

    private void trimRecent() {
        while (recentEvents.size() > MAX_RECENT) {
            recentEvents.remove(0);
        }
    }
}
