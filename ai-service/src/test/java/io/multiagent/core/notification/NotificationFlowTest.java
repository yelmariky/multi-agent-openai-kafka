package io.multiagent.core.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.multiagent.core.infrastructure.kafka.DomainEvent;
import io.multiagent.core.infrastructure.kafka.EventPublisher;
import io.multiagent.core.infrastructure.kafka.KafkaTopics;
import io.multiagent.core.infrastructure.kafka.NotificationPayload;
import io.multiagent.core.notification.consumer.KafkaNotificationConsumer;
import io.multiagent.core.notification.service.ConsultantNotificationService;
import io.multiagent.core.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.multiagent.core.model.Notification;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

/**
 * Test du flux complet de notification :
 *   EventPublisher → Kafka → KafkaNotificationConsumer → ConsultantNotificationService → SSE
 *
 * On simule Kafka avec un mock (pas besoin de broker réel pour ce test unitaire).
 */
@DisplayName("Flux notification complet : EventPublisher → Consumer → SSE consultant")
class NotificationFlowTest {

    private static final String CONSULTANT_EMAIL = "alice@test.com";

    private ConsultantNotificationService consultantSse;
    private NotificationService            adminSse;
    private KafkaNotificationConsumer      consumer;
    private ObjectMapper                   objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper   = new ObjectMapper().registerModule(new JavaTimeModule());
        consultantSse  = new ConsultantNotificationService();
        adminSse       = new NotificationService();
        consumer       = new KafkaNotificationConsumer(adminSse, consultantSse, objectMapper);

        // publisher créé localement dans les tests qui en ont besoin
    }

    // ── Test 1 : Le consumer route correctement vers SSE consultant ──────────

    @Test
    @DisplayName("EXPENSE_APPROVED → consumer → SSE consultant reçoit la notification")
    void expenseApproved_consultantReceivesSSE() throws Exception {
        // 1. Le consultant est abonné au SSE
        SseEmitter emitter = consultantSse.subscribe(CONSULTANT_EMAIL);
        assertThat(emitter).isNotNull();

        // 2. Simuler un message Kafka issu d'une approbation de frais
        String kafkaMessage = buildKafkaMessage(
            NotificationPayload.TARGET_CONSULTANT, CONSULTANT_EMAIL,
            "EXPENSE_APPROVED", "Votre note de frais du 25/06 a été approuvée.", "expense-123");

        // 3. Consumer reçoit et route le message
        assertThatCode(() -> consumer.onNotification(kafkaMessage))
            .doesNotThrowAnyException();

        // 4. La notification est stockée dans le service SSE
        assertThat(consultantSse.getAll(CONSULTANT_EMAIL)).hasSize(1);
        var notif = consultantSse.getAll(CONSULTANT_EMAIL).get(0);
        assertThat(notif.getType()).isEqualTo("EXPENSE_APPROVED");
        assertThat(notif.getMessage()).contains("approuvée");
        assertThat(notif.isRead()).isFalse();
    }

    @Test
    @DisplayName("EXPENSE_REFUSED → SSE consultant avec bon message d'erreur")
    void expenseRefused_consultantReceivesErrorNotification() throws Exception {
        consultantSse.subscribe(CONSULTANT_EMAIL);
        String kafkaMessage = buildKafkaMessage(
            NotificationPayload.TARGET_CONSULTANT, CONSULTANT_EMAIL,
            "EXPENSE_REFUSED", "Justificatif manquant.", "expense-456");

        consumer.onNotification(kafkaMessage);

        var notif = consultantSse.getAll(CONSULTANT_EMAIL).get(0);
        assertThat(notif.getType()).isEqualTo("EXPENSE_REFUSED");
    }

    @Test
    @DisplayName("CRA_VALIDATED → SSE consultant, pas admin")
    void craValidated_onlyConsultantNotified() throws Exception {
        consultantSse.subscribe(CONSULTANT_EMAIL);
        String kafkaMessage = buildKafkaMessage(
            NotificationPayload.TARGET_CONSULTANT, CONSULTANT_EMAIL,
            "CRA_VALIDATED", "Votre CRA de 2026-06 a été validé.", "cra-1");

        consumer.onNotification(kafkaMessage);

        assertThat(consultantSse.getAll(CONSULTANT_EMAIL)).hasSize(1);
        assertThat(adminSse.getAll()).isEmpty(); // admin ne reçoit rien
    }

    // ── Test 2 : Le consumer route correctement vers SSE admin ───────────────

    @Test
    @DisplayName("CRA_SUBMITTED → SSE admin reçoit, consultant ne reçoit pas")
    void craSubmitted_onlyAdminNotified() throws Exception {
        String kafkaMessage = buildKafkaMessage(
            NotificationPayload.TARGET_ADMIN, CONSULTANT_EMAIL,
            "CRA_SUBMITTED", "CRA soumis par alice@test.com", "cra-2");

        consumer.onNotification(kafkaMessage);

        assertThat(adminSse.getAll()).hasSize(1);
        assertThat(adminSse.getAll().get(0).getType()).isEqualTo("CRA_SUBMITTED");
        assertThat(consultantSse.getAll(CONSULTANT_EMAIL)).isEmpty();
    }

    // ── Test 3 : Robustesse du consumer ──────────────────────────────────────

    @Test
    @DisplayName("Message JSON malformé → absorbé sans exception")
    void malformedKafkaMessage_absorbedSilently() {
        assertThatCode(() -> consumer.onNotification("not-json-at-all"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("TARGET=CONSULTANT sans email → ignoré silencieusement")
    void consultantTargetWithoutEmail_ignored() throws Exception {
        String kafkaMessage = buildKafkaMessage(
            NotificationPayload.TARGET_CONSULTANT, null,
            "LEAVE_APPROVED", "Congé approuvé", "leave-1");

        consumer.onNotification(kafkaMessage);

        // Aucune notification envoyée nulle part
        assertThat(adminSse.getAll()).isEmpty();
    }

    // ── Test 4 : EventPublisher → Kafka (avec KafkaTemplate mocké) ───────────

    @Test
    @DisplayName("EventPublisher.notify() publie sur le bon topic Kafka")
    void eventPublisher_publishesToKafkaNotificationTopic() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kt = mock(KafkaTemplate.class);
        EventPublisher pub = new EventPublisher(kt, objectMapper);

        pub.notify(NotificationPayload.TARGET_CONSULTANT, CONSULTANT_EMAIL,
                   "EXPENSE_APPROVED", "Approuvé", "exp-99");

        // Vérifie que KafkaTemplate.send() est appelé avec le bon topic
        verify(kt).send(eq(KafkaTopics.NOTIFICATION), anyString(), anyString());
    }

    // ── Test 5 : Lecture/marquage des notifications ───────────────────────────

    @Test
    @DisplayName("markRead() consultant — marque une notification comme lue")
    void markRead_consultant() throws Exception {
        consultantSse.subscribe(CONSULTANT_EMAIL);
        consumer.onNotification(buildKafkaMessage(
            NotificationPayload.TARGET_CONSULTANT, CONSULTANT_EMAIL,
            "EXPENSE_APPROVED", "Approuvé", "e-1"));
        consumer.onNotification(buildKafkaMessage(
            NotificationPayload.TARGET_CONSULTANT, CONSULTANT_EMAIL,
            "CRA_VALIDATED", "CRA validé", "c-1"));

        assertThat(consultantSse.getUnread(CONSULTANT_EMAIL)).hasSize(2);

        String firstId = consultantSse.getAll(CONSULTANT_EMAIL).get(0).getId();
        consultantSse.markRead(CONSULTANT_EMAIL, firstId);

        assertThat(consultantSse.getUnread(CONSULTANT_EMAIL)).hasSize(1);
    }

    @Test
    @DisplayName("markAllRead() consultant — toutes lues")
    void markAllRead_consultant() throws Exception {
        consultantSse.subscribe(CONSULTANT_EMAIL);
        for (int i = 0; i < 3; i++) {
            consumer.onNotification(buildKafkaMessage(
                NotificationPayload.TARGET_CONSULTANT, CONSULTANT_EMAIL,
                "EXPENSE_APPROVED", "msg " + i, "e-" + i));
        }
        assertThat(consultantSse.getUnread(CONSULTANT_EMAIL)).hasSize(3);

        consultantSse.markAllRead(CONSULTANT_EMAIL);

        assertThat(consultantSse.getUnread(CONSULTANT_EMAIL)).isEmpty();
        assertThat(consultantSse.getAll(CONSULTANT_EMAIL)).hasSize(3); // toujours présentes
    }

    @Test
    @DisplayName("push() sans abonné SSE — notification stockée quand même")
    void push_withoutSubscriber_notificationStoredForLater() throws Exception {
        // Pas d'appel à subscribe() — consultant pas encore connecté
        consumer.onNotification(buildKafkaMessage(
            NotificationPayload.TARGET_CONSULTANT, CONSULTANT_EMAIL,
            "LEAVE_APPROVED", "Congé approuvé", "l-1"));

        // La notification est stockée — sera disponible quand le consultant se connecte
        assertThat(consultantSse.getAll(CONSULTANT_EMAIL)).hasSize(1);
        assertThat(consultantSse.getUnread(CONSULTANT_EMAIL)).hasSize(1);
    }

    // ── Test 6 : Scénario multi-pods (régression clé) ────────────────────────

    @Test
    @DisplayName("EXPENSE_REFUSED → consultant reçoit la notification (scénario exact de la régression)")
    void expenseRefused_consultantReceivesNotification() throws Exception {
        // Simule : consultant connecté au SSE (pod A)
        SseEmitter emitter = consultantSse.subscribe(CONSULTANT_EMAIL);
        assertThat(emitter).isNotNull();

        // Simule : admin refuse l'expense → Kafka publie EXPENSE_REFUSED
        String kafkaMessage = buildKafkaMessage(
            NotificationPayload.TARGET_CONSULTANT, CONSULTANT_EMAIL,
            "EXPENSE_REFUSED", "Justificatif illisible.", "expense-refused-123");

        // Simule : consumer du MÊME pod reçoit le message Kafka (groupId unique par pod)
        consumer.onNotification(kafkaMessage);

        // La notification DOIT être dans le store SSE
        List<io.multiagent.core.model.Notification> all = consultantSse.getAll(CONSULTANT_EMAIL);
        assertThat(all).isNotEmpty();
        assertThat(all.get(0).getType()).isEqualTo("EXPENSE_REFUSED");
        assertThat(all.get(0).getMessage()).contains("illisible");
        assertThat(consultantSse.getUnread(CONSULTANT_EMAIL)).hasSize(1);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private String buildKafkaMessage(String target, String email,
                                      String type, String message, String refId) throws Exception {
        NotificationPayload np = new NotificationPayload(target, email, type, message, refId);
        String payloadJson = objectMapper.writeValueAsString(np);
        DomainEvent event = new DomainEvent(
            type, email != null ? email : "admin", email, null, payloadJson, Instant.now());
        return objectMapper.writeValueAsString(event);
    }
}
