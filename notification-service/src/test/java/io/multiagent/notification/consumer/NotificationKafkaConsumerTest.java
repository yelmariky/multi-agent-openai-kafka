package io.multiagent.notification.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.multiagent.notification.model.DomainEvent;
import io.multiagent.notification.model.NotificationPayload;
import io.multiagent.notification.service.AdminSseRegistry;
import io.multiagent.notification.service.ConsultantSseRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationKafkaConsumer — routing Kafka → SSE")
class NotificationKafkaConsumerTest {

    @Mock AdminSseRegistry      adminSse;
    @Mock ConsultantSseRegistry consultantSse;

    @InjectMocks NotificationKafkaConsumer consumer;

    private static final String EVENT_NAME = "notification";
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ReflectionTestUtils.setField(consumer, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("TARGET=ADMIN → pousse sur adminSse")
    void adminTargetPushesToAdminSse() throws Exception {
        String message = buildMessage(NotificationPayload.TARGET_ADMIN, null,
                "CRA_SUBMITTED", "CRA soumis", "cra-1");

        consumer.onNotification(message);

        ArgumentCaptor<String> payloadCap = ArgumentCaptor.forClass(String.class);
        verify(adminSse).push(eq(EVENT_NAME), payloadCap.capture());
        verify(consultantSse, never()).push(any(), any(), any());
        assertThat(payloadCap.getValue()).contains("CRA_SUBMITTED").contains("cra-1");
    }

    @Test
    @DisplayName("TARGET=CONSULTANT → pousse sur consultantSse avec le bon email")
    void consultantTargetPushesToConsultantSse() throws Exception {
        String message = buildMessage(NotificationPayload.TARGET_CONSULTANT, "alice@test.com",
                "EXPENSE_APPROVED", "Approuvé", "exp-42");

        consumer.onNotification(message);

        ArgumentCaptor<String> emailCap   = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCap = ArgumentCaptor.forClass(String.class);
        verify(consultantSse).push(emailCap.capture(), eq(EVENT_NAME), payloadCap.capture());
        verify(adminSse, never()).push(any(), any());
        assertThat(emailCap.getValue()).isEqualTo("alice@test.com");
        assertThat(payloadCap.getValue()).contains("EXPENSE_APPROVED").contains("exp-42");
    }

    @Test
    @DisplayName("TARGET=CONSULTANT sans email → ignoré")
    void consultantTargetWithoutEmailIgnored() throws Exception {
        String message = buildMessage(NotificationPayload.TARGET_CONSULTANT, null,
                "LEAVE_APPROVED", "msg", "leave-1");

        consumer.onNotification(message);

        verify(consultantSse, never()).push(any(), any(), any());
        verify(adminSse, never()).push(any(), any());
    }

    @Test
    @DisplayName("TARGET=CONSULTANT avec email vide → ignoré")
    void consultantTargetWithBlankEmailIgnored() throws Exception {
        String message = buildMessage(NotificationPayload.TARGET_CONSULTANT, "  ",
                "LEAVE_REFUSED", "msg", "leave-2");

        consumer.onNotification(message);

        verify(consultantSse, never()).push(any(), any(), any());
    }

    @Test
    @DisplayName("JSON malformé → absorbé sans exception")
    void malformedJsonAbsorbedSilently() {
        assertThatCode(() -> consumer.onNotification("not-valid-json"))
                .doesNotThrowAnyException();
        verify(adminSse, never()).push(any(), any());
        verify(consultantSse, never()).push(any(), any(), any());
    }

    @Test
    @DisplayName("Payload invalide → absorbé sans exception")
    void invalidPayloadAbsorbedSilently() throws Exception {
        DomainEvent event = new DomainEvent("T", "id", null, null, "not-json-payload", Instant.now());
        String message = objectMapper.writeValueAsString(event);

        assertThatCode(() -> consumer.onNotification(message))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Payload SSE contient id, type, timestamp, refId")
    void ssePayloadContainsRequiredFields() throws Exception {
        String message = buildMessage(NotificationPayload.TARGET_ADMIN, "c@test.com",
                "CRA_VALIDATED", "Votre CRA a été validé", "cra-99");

        consumer.onNotification(message);

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(adminSse).push(eq(EVENT_NAME), cap.capture());
        assertThat(cap.getValue())
                .contains("\"id\"")
                .contains("\"type\"")
                .contains("\"timestamp\"")
                .contains("CRA_VALIDATED")
                .contains("cra-99");
    }

    private String buildMessage(String target, String email, String type,
                                String message, String refId) throws Exception {
        NotificationPayload np = new NotificationPayload(target, email, type, message, refId);
        String payloadJson = objectMapper.writeValueAsString(np);
        DomainEvent event = new DomainEvent(type,
                email != null ? email : "admin", email, null, payloadJson, Instant.now());
        return objectMapper.writeValueAsString(event);
    }
}
