package io.multiagent.core.cra.service;

import io.multiagent.core.infrastructure.kafka.DomainEvent;
import io.multiagent.core.infrastructure.kafka.EventPublisher;
import io.multiagent.core.infrastructure.kafka.KafkaTopics;
import io.multiagent.core.infrastructure.tenant.TenantContext;
import io.multiagent.core.leave.entity.LeaveRequestEntity;
import io.multiagent.core.leave.repository.LeaveRequestRepository;
import io.multiagent.core.model.CraDayEntry;
import io.multiagent.core.model.CraRequest;
import io.multiagent.core.model.ExpenseItem;
import io.multiagent.core.service.WeaviateService;
import io.multiagent.core.infrastructure.kafka.NotificationPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CraService {

    private final WeaviateService weaviateService;
    private final LeaveRequestRepository leaveRepo;
    private final EventPublisher eventPublisher;

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
                    .filter(e -> e != null && "TRAVAIL".equalsIgnoreCase(e.type()))
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
                cra.projectId(),
                cra.clientValidationRef(),
                cra.clientValidationDate()
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
                toSave.projectId(),
                toSave.clientValidationRef(),
                toSave.clientValidationDate()
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
                cra.projectId(),
                cra.clientValidationRef(),
                cra.clientValidationDate()
        );
        CraRequest submitted = save(toSubmit);
        eventPublisher.notify(NotificationPayload.TARGET_ADMIN, submitted.consultant(),
                "CRA_SUBMITTED",
                "CRA soumis par " + submitted.consultant() + " pour " + submitted.billingMonth(),
                submitted.id());
        eventPublisher.publish(KafkaTopics.CRA_SUBMITTED,
                new DomainEvent("CRA_SUBMITTED", submitted.id(), null,
                        submitted.company(), craPayload(submitted), Instant.now()));
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
                cra.projectId(),
                cra.clientValidationRef(),
                cra.clientValidationDate()
        );
        CraRequest validated = save(toValidate);
        eventPublisher.notify(NotificationPayload.TARGET_CONSULTANT, validated.consultant(),
                "CRA_VALIDATED",
                "Votre CRA de " + validated.billingMonth() + " a été validé par " + validatedBy + ".",
                validated.id());
        eventPublisher.publish(KafkaTopics.CRA_VALIDATED,
                new DomainEvent("CRA_VALIDATED", validated.id(), null,
                        validated.company(), craPayloadWithExtra(validated, "validatedBy", validatedBy), Instant.now()));
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
                cra.projectId(),
                cra.clientValidationRef(),
                cra.clientValidationDate()
        );
        CraRequest refused = save(toRefuse);
        eventPublisher.notify(NotificationPayload.TARGET_CONSULTANT, refused.consultant(),
                "CRA_REFUSED",
                "Votre CRA de " + refused.billingMonth() + " a été refusé"
                        + (reason != null && !reason.isBlank() ? " : " + reason : "."),
                refused.id());
        eventPublisher.publish(KafkaTopics.CRA_REFUSED,
                new DomainEvent("CRA_REFUSED", refused.id(), null,
                        refused.company(), craPayloadWithExtra(refused, "reason", reason), Instant.now()));
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
                cra.projectId(),
                cra.clientValidationRef(),
                cra.clientValidationDate()
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
                cra.projectId(),
                cra.clientValidationRef(),
                cra.clientValidationDate()
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
     * Returns merged absence periods from three sources:
     * 1. absencePeriodsJson stored in mileage expenses (km expenses)
     * 2. ABSENT entries from the saved CRA for this consultant/month
     * 3. Approved leave_requests for this consultant in the given month
     *    (congé = absence automatique, plus besoin de saisie manuelle)
     */
    public List<ExpenseItem.AbsencePeriod> getKmAbsences(String company, String month, String consultant) {
        List<ExpenseItem.AbsencePeriod> kmAbsences   = weaviateService.findKmExpenseAbsences(company, month);
        List<ExpenseItem.AbsencePeriod> craAbsences  = weaviateService.findCraAbsentDays(consultant, company, month);
        List<ExpenseItem.AbsencePeriod> leaveAbsences = approvedLeaveAbsences(consultant, month);

        return java.util.stream.Stream.of(kmAbsences.stream(), craAbsences.stream(), leaveAbsences.stream())
                .flatMap(s -> s)
                .distinct()
                .toList();
    }

    /** Convertit les congés APPROUVÉS du consultant pour le mois en AbsencePeriod. */
    private List<ExpenseItem.AbsencePeriod> approvedLeaveAbsences(String consultant, String month) {
        if (consultant == null || consultant.isBlank() || month == null) return List.of();
        try {
            UUID tenantId = TenantContext.getTenantIdOrNull();
            if (tenantId == null) return List.of();
            List<LeaveRequestEntity> leaves =
                    leaveRepo.findByTenantIdAndConsultantEmailIgnoreCaseAndStatus(tenantId, consultant, "APPROUVEE");
            // Filtrer ceux qui chevauchent le mois
            java.time.YearMonth ym = java.time.YearMonth.parse(month);
            java.time.LocalDate monthStart = ym.atDay(1);
            java.time.LocalDate monthEnd   = ym.atEndOfMonth();
            return leaves.stream()
                    .filter(l -> !l.getEndDate().isBefore(monthStart) && !l.getStartDate().isAfter(monthEnd))
                    .map(l -> new ExpenseItem.AbsencePeriod(
                            l.getStartDate().isBefore(monthStart) ? monthStart.toString() : l.getStartDate().toString(),
                            l.getEndDate().isAfter(monthEnd)      ? monthEnd.toString()   : l.getEndDate().toString()
                    ))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
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
