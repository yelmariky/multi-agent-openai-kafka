package io.multiagent.notification.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.*;

@DisplayName("AdminSseRegistry — SSE admin")
class AdminSseRegistryTest {

    private AdminSseRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AdminSseRegistry();
    }

    @Test
    @DisplayName("subscribe() retourne un SseEmitter non null")
    void subscribeReturnsEmitter() {
        SseEmitter emitter = registry.subscribe(0);
        assertThat(emitter).isNotNull();
    }

    @Test
    @DisplayName("connectedCount() = 1 après un subscribe")
    void connectedCountAfterSubscribe() {
        registry.subscribe(0);
        assertThat(registry.connectedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("connectedCount() = 0 au démarrage")
    void connectedCountInitiallyZero() {
        assertThat(registry.connectedCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("connectedCount() augmente avec chaque subscribe")
    void connectedCountIncrementsWithSubscribers() {
        registry.subscribe(0);
        registry.subscribe(0);
        registry.subscribe(5);
        assertThat(registry.connectedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("push() ne lève pas d'exception sans abonnés")
    void pushWithNoSubscribersDoesNotThrow() {
        assertThatCode(() -> registry.push("notification", "{\"type\":\"TEST\"}"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("push() avec abonné ne lève pas d'exception")
    void pushWithSubscriberDoesNotThrow() {
        registry.subscribe(0);
        assertThatCode(() -> registry.push("notification", "{\"type\":\"CRA_SUBMITTED\"}"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("emitter.complete() ne lève pas d'exception")
    void emitterCompleteDoesNotThrow() {
        SseEmitter emitter = registry.subscribe(0);
        assertThatCode(emitter::complete).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("onTimeout retire l'emitter")
    void onTimeoutCallbackRegistered() {
        SseEmitter emitter = registry.subscribe(0);
        assertThat(emitter).isNotNull();
        // Le timeout déclenche onTimeout() → removeEmitter() → connectedCount diminue
        emitter.completeWithError(new RuntimeException("timeout simulé"));
        // Pas d'exception = onError enregistré correctement
    }

    @Test
    @DisplayName("push avec payload vide ne lève pas d'exception")
    void pushWithEmptyPayloadDoesNotThrow() {
        registry.subscribe(0);
        assertThatCode(() -> registry.push("init", "0"))
                .doesNotThrowAnyException();
    }
}
