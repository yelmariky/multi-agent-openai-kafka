package io.multiagent.notification.controller;

import io.multiagent.notification.service.ConsultantSseRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/consultant-notifications")
@RequiredArgsConstructor
public class ConsultantSseController {

    private final ConsultantSseRegistry registry;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String consultantEmail) {
        return registry.subscribe(consultantEmail);
    }
}
