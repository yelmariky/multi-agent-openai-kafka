package io.multiagent.core.chatbot.controller;

import io.multiagent.core.chatbot.service.ChatbotService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint public du chatbot du site vitrine — anonyme (permitAll).
 * Le champ "message" est inspecté par le PromptGuardFilter avant d'arriver ici.
 */
@RestController
@RequestMapping("/public/chatbot")
@RequiredArgsConstructor
public class ChatbotController {

    private final ChatbotService chatbotService;

    public record ChatRequest(String message) {}
    public record ChatReply(String reply) {}

    @PostMapping
    public ResponseEntity<ChatReply> chat(@RequestBody ChatRequest request, HttpServletRequest http) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new ChatReply("Posez-moi une question sur IA-INSIGHT : tarifs, démo, services IA…"));
        }
        if (!chatbotService.allow(clientIp(http))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new ChatReply("Vous allez un peu vite pour moi ! Réessayez dans une minute, "
                            + "ou appelez-nous directement au 06 75 71 77 43."));
        }
        return ResponseEntity.ok(new ChatReply(chatbotService.reply(request.message())));
    }

    /** IP réelle du visiteur — premier hop du X-Forwarded-For posé par Kong, sinon adresse directe. */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
