package io.multiagent.core.infrastructure.kafka;

import java.time.Instant;

/**
 * Enveloppe générique pour tous les événements domaine publiés sur Kafka.
 *
 * <p>Structure :
 * <ul>
 *   <li>{@code eventType}      — type d'événement (ex: "EXPENSE_CREATED", "CRA_SUBMITTED")</li>
 *   <li>{@code aggregateId}    — identifiant métier de l'entité concernée (UUID Weaviate ou id incrémental)</li>
 *   <li>{@code consultantEmail}— email du consultant concerné (null si non applicable)</li>
 *   <li>{@code companyName}    — société (ex: "IA-INSIGHT")</li>
 *   <li>{@code payload}        — JSON sérialisé du contexte métier complet (contenu dépend du type d'événement)</li>
 *   <li>{@code occurredAt}     — timestamp UTC de l'événement</li>
 * </ul>
 *
 * <p>Les consommateurs Kafka peuvent filtrer par {@code eventType} et désérialiser {@code payload}
 * selon le type attendu.
 */
public record DomainEvent(
        String eventType,
        String aggregateId,
        String consultantEmail,
        String companyName,
        String payload,      // JSON sérialisé du contexte métier
        Instant occurredAt
) {}
