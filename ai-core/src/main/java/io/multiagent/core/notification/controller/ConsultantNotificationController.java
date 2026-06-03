package io.multiagent.core.notification.controller;

import io.multiagent.core.model.Notification;
import io.multiagent.core.notification.service.ConsultantNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/consultant/notifications")
@RequiredArgsConstructor
public class ConsultantNotificationController {

    private final ConsultantNotificationService consultantNotificationService;

    /** SSE — EventSource ne supporte pas les headers custom, auth via query param ?token= (filtré par Spring Security via permitAll sur ce path). */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String consultant) {
        return consultantNotificationService.subscribe(consultant);
    }

    @GetMapping
    public ResponseEntity<List<Notification>> list(@RequestParam String consultant) {
        return ResponseEntity.ok(consultantNotificationService.getUnread(consultant));
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead(@RequestParam String consultant) {
        consultantNotificationService.markAllRead(consultant);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable String id, @RequestParam String consultant) {
        consultantNotificationService.markRead(consultant, id);
        return ResponseEntity.ok().build();
    }
}
