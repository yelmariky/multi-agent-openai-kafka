package io.multiagent.notification.controller;

import io.multiagent.notification.model.Notification;
import io.multiagent.notification.service.AdminNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/admin/notifications")
@RequiredArgsConstructor
public class AdminNotificationController {

    private final AdminNotificationService adminNotificationService;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return adminNotificationService.subscribe();
    }

    @GetMapping
    public ResponseEntity<List<Notification>> list(
            @RequestParam(defaultValue = "false") boolean all) {
        return ResponseEntity.ok(all ? adminNotificationService.getAll() : adminNotificationService.getUnread());
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable String id) {
        adminNotificationService.markRead(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead() {
        adminNotificationService.markAllRead();
        return ResponseEntity.ok().build();
    }
}
