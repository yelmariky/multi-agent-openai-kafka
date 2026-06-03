package io.multiagent.core.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Service de publication d'événements domaine sur Kafka.
 *
 * <p>Kafka est traité en <strong>best-effort</strong> : si le broker est indisponible
 * ou si la sérialisation échoue, l'exception est absorbée et loguée en WARN.
 * Le flux métier principal n'est jamais interrompu par une défaillance Kafka.
 *
 * <p>Le {@link KafkaTemplate} est injecté avec {@code required=false} pour tolérer
 * un contexte sans Kafka (dev local sans broker). Dans ce cas, les appels à
 * {@link #publish(String, DomainEvent)} sont silencieusement ignorés.
 *
 * <p>Utilisation :
 * <pre>
 *   eventPublisher.publish(
 *       KafkaTopics.EXPENSE_CREATED,
 *       new DomainEvent("EXPENSE_CREATED", expenseId, email, company, payloadJson, Instant.now())
 *   );
 * </pre>
 */
@Slf4j
@Service
public class EventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * KafkaTemplate injecté en optional pour ne pas bloquer le démarrage si Kafka est absent.
     * ObjectMapper réutilisé depuis le contexte Spring si disponible, sinon créé localement.
     */
    public EventPublisher(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        // Enregistrement du module JSR-310 pour la sérialisation de java.time.Instant
        this.objectMapper = objectMapper.copy().registerModule(new JavaTimeModule());
    }

    /**
     * Publie un événement domaine sur le topic Kafka spécifié.
     *
     * <p>La clé de message est {@code aggregateId} — cela garantit que tous les événements
     * concernant le même aggregate arrivent dans la même partition (ordre préservé par entité).
     *
     * @param topic  nom du topic (utiliser les constantes {@link KafkaTopics})
     * @param event  événement domaine à publier
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
            // Kafka est best-effort : on absorbe l'erreur pour ne pas propager au flux métier
            log.warn("[Kafka] Echec publication → topic={} eventType={} : {}",
                    topic, event.eventType(), e.getMessage());
        }
    }
}
