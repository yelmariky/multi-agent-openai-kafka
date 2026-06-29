package io.multiagent.notification.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ConsultantSseRegistry — SSE consultant par email")
class ConsultantSseRegistryTest {

    private static final String ALICE = "alice@test.com";
    private static final String NOTIF = "notification";

    private ConsultantSseRegistry registry;

    @BeforeEach
    void setUp() { registry = new ConsultantSseRegistry(); }

    @Test @DisplayName("subscribe() retourne un emitter non null")
    void subscribeReturnsEmitter() {
        assertThat(registry.subscribe(ALICE)).isNotNull();
    }

    @Test @DisplayName("push() sans abonné — pas d'exception")
    void pushNoSubscriberDoesNotThrow() {
        assertThatCode(() -> registry.push("nobody@test.com", NOTIF, "{}"))
                .doesNotThrowAnyException();
    }

    @Test @DisplayName("push() avec abonné — pas d'exception")
    void pushWithSubscriberDoesNotThrow() {
        registry.subscribe(ALICE);
        assertThatCode(() -> registry.push(ALICE, NOTIF, "{\"type\":\"EXPENSE_APPROVED\"}"))
                .doesNotThrowAnyException();
    }

    @Test @DisplayName("Isolation par email — pas de cross-push")
    void pushEmailIsolation() {
        registry.subscribe(ALICE);
        registry.subscribe("bob@test.com");
        assertThatCode(() -> {
            registry.push(ALICE, NOTIF, "{\"msg\":\"pour alice\"}");
            registry.push("bob@test.com", NOTIF, "{\"msg\":\"pour bob\"}");
        }).doesNotThrowAnyException();
    }

    @Test @DisplayName("Plusieurs onglets même consultant — push sans erreur")
    void multipleEmittersSameEmail() {
        registry.subscribe(ALICE);
        registry.subscribe(ALICE);
        assertThatCode(() -> registry.push(ALICE, NOTIF, "{}"))
                .doesNotThrowAnyException();
    }

    @Test @DisplayName("emitter.complete() ne lève pas d'exception")
    void emitterCompleteDoesNotThrow() {
        assertThatCode(() -> registry.subscribe(ALICE).complete())
                .doesNotThrowAnyException();
    }

    @Test @DisplayName("email null — ignoré sans exception")
    void pushNullEmailDoesNotThrow() {
        assertThatCode(() -> registry.push(null, NOTIF, "{}"))
                .doesNotThrowAnyException();
    }

    @Test @DisplayName("email vide — ignoré sans exception")
    void pushBlankEmailDoesNotThrow() {
        assertThatCode(() -> registry.push("   ", NOTIF, "{}"))
                .doesNotThrowAnyException();
    }

    @Test @DisplayName("onError callback enregistré sans exception")
    void onErrorCallbackRegistered() {
        SseEmitter emitter = registry.subscribe(ALICE);
        assertThatCode(() -> emitter.completeWithError(new RuntimeException("simulated")))
                .doesNotThrowAnyException();
    }

    @Test @DisplayName("push sur email inconnu — pas d'exception")
    void pushUnknownEmailDoesNotThrow() {
        assertThatCode(() -> registry.push("unknown@test.com", NOTIF, "{\"type\":\"T\"}"))
                .doesNotThrowAnyException();
    }
}
