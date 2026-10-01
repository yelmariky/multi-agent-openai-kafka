package io.multiagent.notification.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.multiagent.notification.model.DomainEvent;
import io.multiagent.notification.model.NotificationPayload;
import io.multiagent.notification.service.AdminSseRegistry;
import io.multiagent.notification.service.ConsultantSseRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Consomme le topic platform.notifications et dispatche vers le bon registre SSE.
 * C'est le seul composant qui sait comment router une notification vers le bon client.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationKafkaConsumer {

    private final AdminSseRegistry      adminSse;
    private final ConsultantSseRegistry consultantSse;
    private final ObjectMapper          objectMapper;

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 10000),
            dltTopicSuffix = ".DLT",
            autoCreateTopics = "true"
    )
    @KafkaListener(
            topics = "platform.notifications",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onNotification(String message) throws Exception {
        DomainEvent event    = objectMapper.readValue(message, DomainEvent.class);
        NotificationPayload np = objectMapper.readValue(event.payload(), NotificationPayload.class);
        String payload = buildSsePayload(np);

        if (NotificationPayload.TARGET_ADMIN.equals(np.target())) {
            adminSse.push("notification", payload);
            log.info("[→SSE-ADMIN] type={}", np.type());
        } else if (NotificationPayload.TARGET_CONSULTANT.equals(np.target())
                && np.consultantEmail() != null && !np.consultantEmail().isBlank()) {
            consultantSse.push(np.consultantEmail(), "notification", payload);
            log.info("[→SSE-CONSULTANT] email={} type={}", np.consultantEmail(), np.type());
        }
    }

    @DltHandler
    public void onNotificationDlt(String message) {
        log.error("[NotificationConsumer] Message envoyé en DLT après échec des retries : {}", message);
    }

    private String buildSsePayload(NotificationPayload np) {
        String ts   = LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE_TIME);
        String id   = UUID.randomUUID().toString();
        String safe = np.message() != null ? np.message().replace("\\", "\\\\").replace("\"", "\\\"") : "";
        String email = np.consultantEmail() != null ? np.consultantEmail() : "";
        String refId = np.refId() != null ? np.refId() : "";
        return "{\"id\":\"" + id
                + "\",\"type\":\"" + np.type()
                + "\",\"consultantEmail\":\"" + email
                + "\",\"message\":\"" + safe
                + "\",\"timestamp\":\"" + ts
                + "\",\"refId\":\"" + refId + "\"}";
    }
}
