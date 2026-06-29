package io.multiagent.notification.controller;

import io.multiagent.notification.service.AdminSseRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class AdminSseController {

    private final AdminSseRegistry registry;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return registry.subscribe(0);
    }

    @GetMapping("/health")
    public String health() {
        return "notification-service OK — admin connections: " + registry.connectedCount();
    }
}
