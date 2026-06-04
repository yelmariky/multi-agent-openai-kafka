package io.multiagent.notefrais.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Service de publication d'événements domaine sur Kafka.
 * Kafka est traité en best-effort : si le broker est indisponible, l'exception est absorbée.
 */
@Slf4j
@Service
public class EventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public EventPublisher(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper.copy().registerModule(new JavaTimeModule());
    }

    /**
     * Publie un événement domaine sur le topic Kafka spécifié.
     */
    public void publish(String topic, DomainEvent event) {
        if (kafkaTemplate == null) {
            log.debug("[Kafka] KafkaTemplate non disponible — événement {} ignoré (mode sans broker)", event.eventType());
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(event);
            log.info("[Kafka] Publication → topic={} eventType={} aggregateId={} consultant={}",
                    topic, event.eventType(), event.aggregateId(), event.consultantEmail());
            kafkaTemplate.send(topic, event.aggregateId(), json);
        } catch (Exception e) {
            log.warn("[Kafka] Echec publication → topic={} eventType={} : {}",
                    topic, event.eventType(), e.getMessage());
        }
    }
}
