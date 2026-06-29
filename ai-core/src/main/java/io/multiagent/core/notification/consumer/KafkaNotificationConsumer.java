package io.multiagent.core.notification.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.multiagent.core.infrastructure.kafka.DomainEvent;
import io.multiagent.core.infrastructure.kafka.KafkaTopics;
import io.multiagent.core.infrastructure.kafka.NotificationPayload;
import io.multiagent.core.notification.service.ConsultantNotificationService;
import io.multiagent.core.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumer Kafka → SSE pour ai-core.
 * Actif tant que notification-service n'est pas déployé comme service autonome.
 * Consomme platform.notifications et alimente les stores mémoire SSE.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaNotificationConsumer {

    private final NotificationService           adminSse;
    private final ConsultantNotificationService consultantSse;
    private final ObjectMapper                  objectMapper;

    @KafkaListener(
            topics = KafkaTopics.NOTIFICATION,
            groupId = "ai-core-notifications",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onNotification(String message) {
        try {
            DomainEvent      event = objectMapper.readValue(message, DomainEvent.class);
            NotificationPayload np = objectMapper.readValue(event.payload(), NotificationPayload.class);

            if (NotificationPayload.TARGET_ADMIN.equals(np.target())) {
                adminSse.push(np.type(), np.consultantEmail(), np.consultantEmail(), np.message(), np.refId());
            } else if (NotificationPayload.TARGET_CONSULTANT.equals(np.target())
                    && np.consultantEmail() != null && !np.consultantEmail().isBlank()) {
                consultantSse.push(np.consultantEmail(), np.type(), np.message(), np.refId());
            }
        } catch (Exception e) {
            log.warn("[KafkaNotifConsumer] Erreur : {}", e.getMessage());
        }
    }
}
