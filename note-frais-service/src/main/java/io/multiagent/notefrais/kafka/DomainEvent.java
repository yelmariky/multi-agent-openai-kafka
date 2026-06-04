package io.multiagent.notefrais.kafka;

import java.time.Instant;

/**
 * Enveloppe générique pour tous les événements domaine publiés sur Kafka.
 */
public record DomainEvent(
        String eventType,
        String aggregateId,
        String consultantEmail,
        String companyName,
        String payload,
        Instant occurredAt
) {}
