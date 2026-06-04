package io.multiagent.notification.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.multiagent.notification.service.AdminNotificationService;
import io.multiagent.notification.service.ConsultantNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventConsumer {

    private final AdminNotificationService adminNotificationService;
    private final ConsultantNotificationService consultantNotificationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = KafkaTopics.CRA_SUBMITTED, groupId = "notification-service")
    public void onCraSubmitted(String message) {
        try {
            DomainEvent event = objectMapper.readValue(message, DomainEvent.class);
            JsonNode payload = objectMapper.readTree(event.payload());
            String consultant = payload.path("consultant").asText("");
            String billingMonth = payload.path("billingMonth").asText("");
            adminNotificationService.push(
                "CRA_SUBMITTED", consultant, event.consultantEmail(),
                "CRA soumis par " + consultant + " pour " + billingMonth,
                event.aggregateId()
            );
            log.info("[Kafka] CRA_SUBMITTED → admin SSE, consultant={}", consultant);
        } catch (Exception e) {
            log.warn("[Kafka] onCraSubmitted parse error: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = KafkaTopics.CRA_VALIDATED, groupId = "notification-service")
    public void onCraValidated(String message) {
        try {
            DomainEvent event = objectMapper.readValue(message, DomainEvent.class);
            JsonNode payload = objectMapper.readTree(event.payload());
            String consultant = payload.path("consultant").asText("");
            String billingMonth = payload.path("billingMonth").asText("");
            String validatedBy = payload.path("validatedBy").asText("");
            consultantNotificationService.push(
                consultant, "CRA_VALIDATED",
                "Votre CRA de " + billingMonth + " a été validé par " + validatedBy + ".",
                event.aggregateId()
            );
            log.info("[Kafka] CRA_VALIDATED → consultant SSE, consultant={}", consultant);
        } catch (Exception e) {
            log.warn("[Kafka] onCraValidated parse error: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = KafkaTopics.CRA_REFUSED, groupId = "notification-service")
    public void onCraRefused(String message) {
        try {
            DomainEvent event = objectMapper.readValue(message, DomainEvent.class);
            JsonNode payload = objectMapper.readTree(event.payload());
            String consultant = payload.path("consultant").asText("");
            String billingMonth = payload.path("billingMonth").asText("");
            String reason = payload.path("reason").asText("");
            String msg = "Votre CRA de " + billingMonth + " a été refusé"
                + (reason.isBlank() ? "." : " : " + reason);
            consultantNotificationService.push(consultant, "CRA_REFUSED", msg, event.aggregateId());
            log.info("[Kafka] CRA_REFUSED → consultant SSE, consultant={}", consultant);
        } catch (Exception e) {
            log.warn("[Kafka] onCraRefused parse error: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = KafkaTopics.EXPENSE_CREATED, groupId = "notification-service")
    public void onExpenseCreated(String message) {
        try {
            DomainEvent event = objectMapper.readValue(message, DomainEvent.class);
            JsonNode payload = objectMapper.readTree(event.payload());
            String consultant = payload.path("consultant").asText(event.consultantEmail() != null ? event.consultantEmail() : "");
            adminNotificationService.push(
                "EXPENSE_CREATED", consultant, event.consultantEmail(),
                "Nouvelle note de frais créée" + (consultant.isBlank() ? "" : " par " + consultant),
                event.aggregateId()
            );
        } catch (Exception e) {
            log.warn("[Kafka] onExpenseCreated parse error: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = KafkaTopics.EXPENSE_APPROVED, groupId = "notification-service")
    public void onExpenseApproved(String message) {
        try {
            DomainEvent event = objectMapper.readValue(message, DomainEvent.class);
            JsonNode payload = objectMapper.readTree(event.payload());
            String consultant = payload.path("consultant").asText("");
            adminNotificationService.push(
                "EXPENSE_APPROVED", consultant, event.consultantEmail(),
                "Note de frais approuvée" + (consultant.isBlank() ? "" : " pour " + consultant),
                event.aggregateId()
            );
        } catch (Exception e) {
            log.warn("[Kafka] onExpenseApproved parse error: {}", e.getMessage());
        }
    }
}
