package io.multiagent.core.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("EventPublisher — publication Kafka best-effort")
class EventPublisherTest {

    private KafkaTemplate<String, String> kafkaTemplate;
    private EventPublisher publisher;
    private EventPublisher publisherNoKafka;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        publisher        = new EventPublisher(kafkaTemplate, mapper);
        publisherNoKafka = new EventPublisher(null, mapper);   // Kafka absent (dev local)
    }

    @Test
    @DisplayName("publish() envoie le JSON sérialisé sur le bon topic avec aggregateId comme clé")
    void publish_sendsSerializedEventToTopic() {
        DomainEvent event = new DomainEvent("CRA_SUBMITTED", "cra-123",
                "alice@test.com", "IA-INSIGHT", "{}", Instant.now());

        publisher.publish(KafkaTopics.CRA_SUBMITTED, event);

        ArgumentCaptor<String> keyCaptor     = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.CRA_SUBMITTED), keyCaptor.capture(), messageCaptor.capture());

        assertThat(keyCaptor.getValue()).isEqualTo("cra-123");
        assertThat(messageCaptor.getValue()).contains("CRA_SUBMITTED").contains("alice@test.com");
    }

    @Test
    @DisplayName("publish() ne lève jamais d'exception si KafkaTemplate est null (dev local)")
    void publish_withoutKafka_doesNotThrow() {
        DomainEvent event = new DomainEvent("TEST", "id-1", null, null, "{}", Instant.now());
        assertThatCode(() -> publisherNoKafka.publish("any.topic", event))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("publish() absorbe les exceptions Kafka sans propager au flux métier")
    void publish_kafkaException_absorbedSilently() {
        doThrow(new RuntimeException("Broker down")).when(kafkaTemplate).send(anyString(), anyString(), anyString());
        DomainEvent event = new DomainEvent("CRA_SUBMITTED", "id", null, null, "{}", Instant.now());
        assertThatCode(() -> publisher.publish(KafkaTopics.CRA_SUBMITTED, event))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("notify() construit un NotificationPayload correct et publie sur NOTIFICATION topic")
    void notify_buildsCorrectPayloadAndPublishes() {
        publisher.notify(NotificationPayload.TARGET_CONSULTANT, "bob@test.com",
                "EXPENSE_APPROVED", "Votre note de frais a été approuvée.", "expense-99");

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.NOTIFICATION), anyString(), msgCaptor.capture());

        String json = msgCaptor.getValue();
        assertThat(json).contains("EXPENSE_APPROVED")
                        .contains("bob@test.com")
                        .contains("CONSULTANT")
                        .contains("expense-99");
    }

    @Test
    @DisplayName("notify() avec Kafka absent ne lève pas d'exception")
    void notify_withoutKafka_doesNotThrow() {
        assertThatCode(() -> publisherNoKafka.notify(
                NotificationPayload.TARGET_ADMIN, null, "CRA_SUBMITTED", "msg", "ref"))
                .doesNotThrowAnyException();
    }
}
