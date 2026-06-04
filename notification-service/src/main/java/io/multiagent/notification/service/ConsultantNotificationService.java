package io.multiagent.notification.service;

import io.multiagent.notification.model.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@Slf4j
public class ConsultantNotificationService {

    private final Map<String, List<SseEmitter>> emittersByConsultant = new ConcurrentHashMap<>();
    private final Map<String, List<Notification>> notificationsByConsultant = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String consultantName) {
        SseEmitter emitter = new SseEmitter(0L);
        emittersByConsultant.computeIfAbsent(consultantName, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(consultantName, emitter));
        emitter.onTimeout(()    -> removeEmitter(consultantName, emitter));
        emitter.onError(e       -> removeEmitter(consultantName, emitter));
        try {
            long unread = getUnread(consultantName).size();
            emitter.send(SseEmitter.event().name("init").data(unread));
        } catch (Exception ignored) {}
        return emitter;
    }

    public void push(String consultantName, String type, String message, String refId) {
        Notification n = Notification.builder()
                .id(UUID.randomUUID().toString())
                .type(type)
                .consultantName(consultantName)
                .message(message)
                .timestamp(LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE_TIME))
                .read(false)
                .refId(refId)
                .build();
        notificationsByConsultant.computeIfAbsent(consultantName, k -> new CopyOnWriteArrayList<>()).add(0, n);

        String payload = "{\"id\":\"" + n.getId() + "\",\"type\":\"" + n.getType()
                + "\",\"message\":\"" + escape(n.getMessage())
                + "\",\"timestamp\":\"" + n.getTimestamp()
                + "\",\"refId\":\"" + safeStr(n.getRefId()) + "\"}";

        List<SseEmitter> emitters = emittersByConsultant.getOrDefault(consultantName, List.of());
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter e : emitters) {
            try {
                e.send(SseEmitter.event().name("notification").data(payload));
            } catch (Exception ex) {
                dead.add(e);
            }
        }
        emitters.removeAll(dead);
    }

    public List<Notification> getUnread(String consultantName) {
        return notificationsByConsultant.getOrDefault(consultantName, List.of())
                .stream().filter(n -> !n.isRead()).toList();
    }

    public List<Notification> getAll(String consultantName) {
        return List.copyOf(notificationsByConsultant.getOrDefault(consultantName, List.of()));
    }

    public void markRead(String consultantName, String id) {
        notificationsByConsultant.getOrDefault(consultantName, List.of())
                .stream().filter(n -> n.getId().equals(id)).forEach(n -> n.setRead(true));
    }

    public void markAllRead(String consultantName) {
        notificationsByConsultant.getOrDefault(consultantName, List.of())
                .forEach(n -> n.setRead(true));
    }

    private void removeEmitter(String consultantName, SseEmitter emitter) {
        List<SseEmitter> list = emittersByConsultant.get(consultantName);
        if (list != null) list.remove(emitter);
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String safeStr(String s) {
        return s == null ? "" : s;
    }
}
