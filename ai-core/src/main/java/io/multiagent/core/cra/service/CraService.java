package io.multiagent.core.cra.service;

import io.multiagent.core.infrastructure.kafka.DomainEvent;
import io.multiagent.core.infrastructure.kafka.EventPublisher;
import io.multiagent.core.infrastructure.kafka.KafkaTopics;
import io.multiagent.core.model.CraDayEntry;
import io.multiagent.core.model.CraRequest;
import io.multiagent.core.model.ExpenseItem;
import io.multiagent.core.service.WeaviateService;
import io.multiagent.core.notification.service.NotificationService;
import io.multiagent.core.notification.service.ConsultantNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CraService {

    private final WeaviateService weaviateService;
    private final NotificationService notificationService;
    private final ConsultantNotificationService consultantNotificationService;

    // Kafka best-effort : null si le broker n'est pas disponible en dev local
    @Autowired(required = false)
    private EventPublisher eventPublisher;

    /**
     * Upsert a CRA in Weaviate. Sets status = BROUILLON if null/blank.
     * Recalculates totalDays from entries.
     * Returns the saved CRA with the Weaviate UUID.
     */
    public CraRequest save(CraRequest cra) {
        String status = (cra.status() == null || cra.status().isBlank()) ? "BROUILLON" : cra.status();
        double totalDays = 0.0;
        if (cra.entries() != null) {
            totalDays = cra.entries().stream()
                    .filter(e -> e != null && e.value() > 0)
                    .mapToDouble(CraDayEntry::value)
                    .sum();
        }
        CraRequest toSave = new CraRequest(
                cra.id(),
                cra.consultant(),
                cra.company(),
                cra.clientCompany(),
                cra.clientContactEmail(),
                cra.billingMonth(),
                cra.entries(),
                totalDays,
                status,
                cra.submittedAt(),
                cra.validatedAt(),
                cra.validatedBy(),
                cra.refusedReason(),
                cra.missionId(),
                cra.projectId()
        );
        String uuid = weaviateService.indexCra(toSave);
        return new CraRequest(
                uuid,
                toSave.consultant(),
                toSave.company(),
                toSave.clientCompany(),
                toSave.clientContactEmail(),
                toSave.billingMonth(),
                toSave.entries(),
                toSave.totalDays(),
                toSave.status(),
                toSave.submittedAt(),
                toSave.validatedAt(),
                toSave.validatedBy(),
                toSave.refusedReason(),
                toSave.missionId(),
                toSave.projectId()
        );
    }

    /**
     * Transitions CRA from BROUILLON to SOUMIS and records submission timestamp.
     */
    public CraRequest submit(CraRequest cra) {
        String submittedAt = LocalDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_DATE_TIME);
        CraRequest toSubmit = new CraRequest(
                cra.id(),
                cra.consultant(),
                cra.company(),
                cra.clientCompany(),
                cra.clientContactEmail(),
                cra.billingMonth(),
                cra.entries(),
                cra.totalDays(),
                "SOUMIS",
                submittedAt,
                cra.validatedAt(),
                cra.validatedBy(),
                null,  // clear refusedReason on resubmit
                cra.missionId(),
                cra.projectId()
        );
        CraRequest submitted = save(toSubmit);
        notificationService.push(
                "CRA_SUBMITTED",
                submitted.consultant(),
                null,
                "CRA soumis par " + submitted.consultant() + " pour " + submitted.billingMonth(),
                submitted.id()
        );
        if (eventPublisher != null) {
            eventPublisher.publish(
                    KafkaTopics.CRA_SUBMITTED,
                    new DomainEvent("CRA_SUBMITTED", submitted.id(), null,
                            submitted.company(), craPayload(submitted), Instant.now())
            );
        }
        return submitted;
    }

    /**
     * Transitions CRA from SOUMIS to VALIDE and records validation info.
     */
    public CraRequest validate(CraRequest cra, String validatedBy) {
        String validatedAt = LocalDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_DATE_TIME);
        CraRequest toValidate = new CraRequest(
                cra.id(),
                cra.consultant(),
                cra.company(),
                cra.clientCompany(),
                cra.clientContactEmail(),
                cra.billingMonth(),
                cra.entries(),
                cra.totalDays(),
                "VALIDE",
                cra.submittedAt(),
                validatedAt,
                validatedBy,
                null,
                cra.missionId(),
                cra.projectId()
        );
        CraRequest validated = save(toValidate);
        consultantNotificationService.push(
                validated.consultant(),
                "CRA_VALIDATED",
                "Votre CRA de " + validated.billingMonth() + " a été validé par " + validatedBy + ".",
                validated.id()
        );
        if (eventPublisher != null) {
            eventPublisher.publish(
                    KafkaTopics.CRA_VALIDATED,
                    new DomainEvent("CRA_VALIDATED", validated.id(), null,
                            validated.company(), craPayloadWithExtra(validated, "validatedBy", validatedBy), Instant.now())
            );
        }
        return validated;
    }

    /**
     * Refuses a CRA and resets it to BROUILLON with a reason.
     */
    public CraRequest refuse(CraRequest cra, String reason) {
        CraRequest toRefuse = new CraRequest(
                cra.id(),
                cra.consultant(),
                cra.company(),
                cra.clientCompany(),
                cra.clientContactEmail(),
                cra.billingMonth(),
                cra.entries(),
                cra.totalDays(),
                "REFUSE",
                null,           // clear submittedAt
                cra.validatedAt(),
                cra.validatedBy(),
                reason,
                cra.missionId(),
                cra.projectId()
        );
        CraRequest refused = save(toRefuse);
        consultantNotificationService.push(
                refused.consultant(),
                "CRA_REFUSED",
                "Votre CRA de " + refused.billingMonth() + " a été refusé"
                        + (reason != null && !reason.isBlank() ? " : " + reason : "."),
                refused.id()
        );
        if (eventPublisher != null) {
            eventPublisher.publish(
                    KafkaTopics.CRA_REFUSED,
                    new DomainEvent("CRA_REFUSED", refused.id(), null,
                            refused.company(), craPayloadWithExtra(refused, "reason", reason), Instant.now())
            );
        }
        return refused;
    }

    /**
     * Allows the consultant to retract a SOUMIS CRA back to BROUILLON before the admin acts.
     * Clears submittedAt so the CRA is treated as a fresh draft.
     */
    public CraRequest recall(CraRequest cra) {
        CraRequest toRecall = new CraRequest(
                cra.id(),
                cra.consultant(),
                cra.company(),
                cra.clientCompany(),
                cra.clientContactEmail(),
                cra.billingMonth(),
                cra.entries(),
                cra.totalDays(),
                "BROUILLON",
                null,   // clear submittedAt
                cra.validatedAt(),
                cra.validatedBy(),
                null,   // clear refusedReason
                cra.missionId(),
                cra.projectId()
        );
        return save(toRecall);
    }

    /**
     * Resets a VALIDE or REFUSE CRA back to SOUMIS so the admin can re-review.
     * Clears validatedAt/validatedBy and refusedReason; preserves submittedAt.
     */
    public CraRequest reopen(CraRequest cra) {
        CraRequest toReopen = new CraRequest(
                cra.id(),
                cra.consultant(),
                cra.company(),
                cra.clientCompany(),
                cra.clientContactEmail(),
                cra.billingMonth(),
                cra.entries(),
                cra.totalDays(),
                "SOUMIS",
                cra.submittedAt(),
                null,   // clear validatedAt
                null,   // clear validatedBy
                null,   // clear refusedReason
                cra.missionId(),
                cra.projectId()
        );
        return save(toReopen);
    }

    /**
     * Returns CRAs within a period, optionally filtered by consultant and company.
     */
    public List<Map<String, Object>> report(String start, String end, String consultant, String company) {
        return weaviateService.findCrasByPeriod(start, end, consultant, company);
    }

    /**
     * Returns merged absence periods from two sources:
     * 1. absencePeriodsJson stored in mileage expenses (km expenses created with absence periods)
     * 2. ABSENT entries from the saved CRA for this consultant/company/month
     * Half-days (0.5j, type=TRAVAIL) are NOT included — consultant still drove to work.
     */
    public List<ExpenseItem.AbsencePeriod> getKmAbsences(String company, String month, String consultant) {
        List<ExpenseItem.AbsencePeriod> kmAbsences = weaviateService.findKmExpenseAbsences(company, month);
        List<ExpenseItem.AbsencePeriod> craAbsences = weaviateService.findCraAbsentDays(consultant, company, month);
        List<ExpenseItem.AbsencePeriod> merged = new ArrayList<>(kmAbsences);
        merged.addAll(craAbsences);
        return merged;
    }

    /**
     * Deletes a CRA by its Weaviate UUID.
     */
    public void delete(String id) {
        weaviateService.deleteCra(id);
    }

    // --- Helpers Kafka ---

    private String craPayload(CraRequest cra) {
        return "{\"consultant\":\"" + cra.consultant()
                + "\",\"billingMonth\":\"" + cra.billingMonth() + "\"}";
    }

    private String craPayloadWithExtra(CraRequest cra, String extraKey, String extraValue) {
        return "{\"consultant\":\"" + cra.consultant()
                + "\",\"billingMonth\":\"" + cra.billingMonth()
                + "\",\"" + extraKey + "\":\"" + extraValue + "\"}";
    }
}
