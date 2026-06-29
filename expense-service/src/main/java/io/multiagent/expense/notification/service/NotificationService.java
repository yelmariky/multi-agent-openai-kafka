package io.multiagent.expense.notification.service;

import io.multiagent.expense.model.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@Slf4j
public class NotificationService {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final List<Notification> notifications = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L); // no timeout
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        // send current unread count on connect
        try {
            long unread = notifications.stream().filter(n -> !n.isRead()).count();
            emitter.send(SseEmitter.event().name("init").data(unread));
        } catch (Exception ex) {
            log.debug("SSE init send failed (client already disconnected): {}", ex.getMessage());
        }
        return emitter;
    }

    /**
     * Pousse une notification SSE de façon asynchrone.
     * Exécuté sur un thread pool séparé : un Broken pipe sur un client SSE déconnecté
     * ne pollue plus le thread de la requête principale et n'est pas remonté
     * au DispatcherServlet comme une erreur 500.
     */
    @Async
    public void push(String type, String consultantName, String consultantEmail, String message, String refId) {
        Notification n = Notification.builder()
                .id(UUID.randomUUID().toString())
                .type(type)
                .consultantName(consultantName)
                .consultantEmail(consultantEmail)
                .message(message)
                .timestamp(LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE_TIME))
                .read(false)
                .refId(refId)
                .build();
        notifications.add(0, n);
        // push to all SSE clients
        String payload = "{\"id\":\"" + n.getId() + "\",\"type\":\"" + n.getType()
                + "\",\"consultantEmail\":\"" + escape(safeStr(n.getConsultantEmail()))
                + "\",\"message\":\"" + escape(n.getMessage())
                + "\",\"timestamp\":\"" + n.getTimestamp()
                + "\",\"refId\":\"" + safeStr(n.getRefId()) + "\"}";
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter e : emitters) {
            try {
                e.send(SseEmitter.event().name("notification").data(payload));
            } catch (Exception ex) {
                log.debug("SSE client disconnected, removing emitter: {}", ex.getMessage());
                dead.add(e);
            }
        }
        emitters.removeAll(dead);
    }

    public List<Notification> getUnread() {
        return notifications.stream().filter(n -> !n.isRead()).toList();
    }

    public List<Notification> getAll() {
        return List.copyOf(notifications);
    }

    public void markRead(String id) {
        notifications.stream()
                .filter(n -> n.getId().equals(id))
                .forEach(n -> n.setRead(true));
    }

    public void markAllRead() {
        notifications.forEach(n -> n.setRead(true));
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String safeStr(String s) {
        return s == null ? "" : s;
    }
}
