package io.multiagent.notification.kafka;

import java.time.Instant;

public record DomainEvent(
        String eventType,
        String aggregateId,
        String consultantEmail,
        String companyName,
        String payload,
        Instant occurredAt
) {}
