package io.multiagent.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registre des connexions SSE admin (console de gestion).
 * Toutes les instances admin partagent le même flux — on diffuse à tous.
 */
@Service
@Slf4j
public class AdminSseRegistry {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe(long unreadCount) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(()    -> emitters.remove(emitter));
        emitter.onError(e       -> emitters.remove(emitter));
        try {
            emitter.send(SseEmitter.event().name("init").data(unreadCount));
        } catch (Exception ex) {
            log.debug("[SSE-ADMIN] Init send failed (client already disconnected): {}", ex.getMessage());
        }
        return emitter;
    }

    public void push(String eventName, String payload) {
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter e : emitters) {
            try {
                e.send(SseEmitter.event().name(eventName).data(payload));
            } catch (Exception ex) {
                log.debug("[SSE-ADMIN] Broken pipe — emitter retiré");
                dead.add(e);
            }
        }
        emitters.removeAll(dead);
    }

    public int connectedCount() { return emitters.size(); }
}
