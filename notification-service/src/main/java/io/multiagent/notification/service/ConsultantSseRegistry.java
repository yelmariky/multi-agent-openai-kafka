package io.multiagent.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registre des connexions SSE consultant, indexé par email.
 * Un consultant peut avoir plusieurs onglets ouverts → plusieurs emitters.
 */
@Service
@Slf4j
public class ConsultantSseRegistry {

    private final Map<String, List<SseEmitter>> byEmail = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String email) {
        SseEmitter emitter = new SseEmitter(0L);
        byEmail.computeIfAbsent(email, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(email, emitter));
        emitter.onTimeout(()    -> remove(email, emitter));
        emitter.onError(e       -> remove(email, emitter));
        try { emitter.send(SseEmitter.event().name("init").data(0)); } catch (Exception ignored) {}
        return emitter;
    }

    public void push(String email, String eventName, String payload) {
        if (email == null || email.isBlank()) return;
        List<SseEmitter> emitters = byEmail.getOrDefault(email, List.of());
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter e : emitters) {
            try {
                e.send(SseEmitter.event().name(eventName).data(payload));
            } catch (Exception ex) {
                log.debug("[SSE-CONSULTANT] Broken pipe email={}", email);
                dead.add(e);
            }
        }
        if (!dead.isEmpty()) emitters.removeAll(dead);
    }

    private void remove(String email, SseEmitter emitter) {
        List<SseEmitter> list = byEmail.get(email);
        if (list != null) list.remove(emitter);
    }
}
