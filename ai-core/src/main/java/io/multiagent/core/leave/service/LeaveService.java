package io.multiagent.core.leave.service;

import io.multiagent.core.infrastructure.tenant.TenantContext;
import io.multiagent.core.leave.entity.LeaveBalanceEntity;
import io.multiagent.core.leave.entity.LeaveRequestEntity;
import io.multiagent.core.leave.repository.LeaveBalanceRepository;
import io.multiagent.core.leave.repository.LeaveRequestRepository;
import io.multiagent.core.infrastructure.kafka.EventPublisher;
import io.multiagent.core.infrastructure.kafka.NotificationPayload;
import io.multiagent.core.service.WeaviateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class LeaveService {

    private final LeaveRequestRepository leaveRepo;
    private final LeaveBalanceRepository balanceRepo;
    private final WeaviateService        weaviateService;
    private final EventPublisher         eventPublisher;

    /** Consultant — soumet une demande de congé. */
    @Transactional
    public Map<String, Object> request(String consultantEmail, String type, String startStr, String endStr, String reason) {
        UUID tenantId = TenantContext.getTenantId();
        LocalDate start = LocalDate.parse(startStr);
        LocalDate end   = LocalDate.parse(endStr);
        if (end.isBefore(start)) throw new IllegalArgumentException("La date de fin doit être après la date de début.");

        double days = countWorkingDays(start, end);

        LeaveRequestEntity entity = new LeaveRequestEntity();
        entity.setTenantId(tenantId);
        entity.setConsultantEmail(consultantEmail.toLowerCase().trim());
        entity.setType(type.toUpperCase());
        entity.setStartDate(start);
        entity.setEndDate(end);
        entity.setDaysCount(BigDecimal.valueOf(days));
        entity.setReason(reason);
        entity.setStatus("DEMANDEE");
        leaveRepo.save(entity);
        log.info("Leave requested: {} {} {}-{} ({} days)", consultantEmail, type, start, end, days);

        // Injecter immédiatement les jours comme absences ⏳ (en attente) dans le CRA
        weaviateService.applyPendingLeaveAbsences(consultantEmail, start, end, tenantId);

        // Notifier l'admin via Kafka → notification-service → SSE admin
        String typeLabel = leaveTypeLabel(type);
        eventPublisher.notify(NotificationPayload.TARGET_ADMIN, consultantEmail,
                "LEAVE_REQUESTED",
                consultantEmail + " a demandé " + days + "j de " + typeLabel
                        + " du " + start + " au " + end + ".",
                entity.getId().toString());

        return toMap(entity);
    }

    /** Admin — approuve une demande. */
    @Transactional
    public Map<String, Object> approve(UUID id, String approvedBy) {
        UUID tenantId = TenantContext.getTenantId();
        LeaveRequestEntity entity = findSecure(id, tenantId);
        entity.setStatus("APPROUVEE");
        entity.setApprovedBy(approvedBy);
        entity.setApprovedAt(Instant.now());
        leaveRepo.save(entity);
        deductBalance(entity);

        // Injecter les jours de congé comme entrées ABSENT dans le CRA du consultant
        weaviateService.applyLeaveAbsences(
                entity.getConsultantEmail(), entity.getId(),
                entity.getStartDate(), entity.getEndDate(), tenantId);

        // Notifier le consultant
        String approvedMsg = "Votre demande de " + leaveTypeLabel(entity.getType())
                + " du " + entity.getStartDate() + " au " + entity.getEndDate()
                + " (" + entity.getDaysCount().stripTrailingZeros().toPlainString() + "j) a été approuvée"
                + " et ajoutée à votre CRA.";
        eventPublisher.notify(NotificationPayload.TARGET_CONSULTANT,
                entity.getConsultantEmail(), "LEAVE_APPROVED", approvedMsg, entity.getId().toString());

        return toMap(entity);
    }

    /** Admin — refuse une demande. */
    @Transactional
    public Map<String, Object> refuse(UUID id, String reason) {
        UUID tenantId = TenantContext.getTenantId();
        LeaveRequestEntity entity = findSecure(id, tenantId);
        entity.setStatus("REFUSEE");
        entity.setRefusedReason(reason);
        leaveRepo.save(entity);

        // Retirer les entrées ABSENT du CRA si elles avaient été injectées
        weaviateService.removeLeaveAbsences(
                entity.getConsultantEmail(),
                entity.getStartDate(), entity.getEndDate(), tenantId);

        // Notifier le consultant
        String msg = "Votre demande de " + leaveTypeLabel(entity.getType())
                + " du " + entity.getStartDate() + " au " + entity.getEndDate()
                + " a été refusée"
                + (reason != null && !reason.isBlank() ? " : " + reason : ".")
                + " Les absences ont été retirées de votre CRA.";
        eventPublisher.notify(NotificationPayload.TARGET_CONSULTANT,
                entity.getConsultantEmail(), "LEAVE_REFUSED", msg, entity.getId().toString());

        return toMap(entity);
    }

    /** Toutes les demandes du tenant (admin). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAll(String status) {
        UUID tenantId = TenantContext.getTenantId();
        List<LeaveRequestEntity> list = status != null && !status.isBlank()
                ? leaveRepo.findByTenantIdAndStatus(tenantId, status.toUpperCase())
                : leaveRepo.findByTenantId(tenantId);
        return list.stream().map(this::toMap).toList();
    }

    /** Demandes d'un consultant (espace personnel). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listMine(String consultantEmail, String status) {
        UUID tenantId = TenantContext.getTenantId();
        String email  = consultantEmail.toLowerCase().trim();
        List<LeaveRequestEntity> list = status != null && !status.isBlank()
                ? leaveRepo.findByTenantIdAndConsultantEmailIgnoreCaseAndStatus(tenantId, email, status.toUpperCase())
                : leaveRepo.findByTenantIdAndConsultantEmailIgnoreCase(tenantId, email);
        return list.stream().map(this::toMap).toList();
    }

    /** Solde CP/RTT d'un consultant pour une année. */
    @Transactional(readOnly = true)
    public Map<String, Object> balance(String consultantEmail, int year) {
        UUID tenantId = TenantContext.getTenantId();
        LeaveBalanceEntity bal = balanceRepo
                .findByTenantIdAndConsultantEmailIgnoreCaseAndYear(tenantId, consultantEmail, year)
                .orElseGet(() -> defaultBalance(tenantId, consultantEmail, year));
        return balanceToMap(bal);
    }

    /** Admin — initialise ou met à jour le solde d'un consultant. */
    @Transactional
    public Map<String, Object> setBalance(String consultantEmail, int year, double cpInitial, double rttInitial) {
        UUID tenantId = TenantContext.getTenantId();
        LeaveBalanceEntity bal = balanceRepo
                .findByTenantIdAndConsultantEmailIgnoreCaseAndYear(tenantId, consultantEmail, year)
                .orElse(defaultBalance(tenantId, consultantEmail, year));
        bal.setCpInitial(BigDecimal.valueOf(cpInitial));
        bal.setRttInitial(BigDecimal.valueOf(rttInitial));
        balanceRepo.save(bal);
        return balanceToMap(bal);
    }

    // -------------------------------------------------------------------------

    private String leaveTypeLabel(String type) {
        return switch (type != null ? type.toUpperCase() : "") {
            case "CP"        -> "congés payés";
            case "RTT"       -> "RTT";
            case "MALADIE"   -> "congé maladie";
            case "FORMATION" -> "formation";
            default          -> "congé";
        };
    }

    private void deductBalance(LeaveRequestEntity leave) {
        String type = leave.getType();
        if (!"CP".equals(type) && !"RTT".equals(type)) return;
        int year = leave.getStartDate().getYear();
        UUID tenantId = leave.getTenantId();
        LeaveBalanceEntity bal = balanceRepo
                .findByTenantIdAndConsultantEmailIgnoreCaseAndYear(tenantId, leave.getConsultantEmail(), year)
                .orElse(defaultBalance(tenantId, leave.getConsultantEmail(), year));
        BigDecimal days = leave.getDaysCount();
        if ("CP".equals(type))  bal.setCpTaken(bal.getCpTaken().add(days));
        if ("RTT".equals(type)) bal.setRttTaken(bal.getRttTaken().add(days));
        balanceRepo.save(bal);
    }

    private LeaveBalanceEntity defaultBalance(UUID tenantId, String email, int year) {
        LeaveBalanceEntity b = new LeaveBalanceEntity();
        b.setTenantId(tenantId);
        b.setConsultantEmail(email.toLowerCase().trim());
        b.setYear(year);
        b.setCpInitial(BigDecimal.valueOf(25.0));
        b.setCpTaken(BigDecimal.ZERO);
        b.setRttInitial(BigDecimal.valueOf(12.0));
        b.setRttTaken(BigDecimal.ZERO);
        return b;
    }

    private LeaveRequestEntity findSecure(UUID id, UUID tenantId) {
        return leaveRepo.findById(id)
                .filter(e -> tenantId.equals(e.getTenantId()))
                .orElseThrow(() -> new IllegalArgumentException("Demande introuvable : " + id));
    }

    private int countWorkingDays(LocalDate start, LocalDate end) {
        int count = 0;
        LocalDate d = start;
        while (!d.isAfter(end)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) count++;
            d = d.plusDays(1);
        }
        return count;
    }

    private Map<String, Object> toMap(LeaveRequestEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",               e.getId());
        m.put("consultantEmail",  e.getConsultantEmail());
        m.put("type",             e.getType());
        m.put("startDate",        e.getStartDate());
        m.put("endDate",          e.getEndDate());
        m.put("daysCount",        e.getDaysCount());
        m.put("status",           e.getStatus());
        m.put("reason",           e.getReason());
        m.put("refusedReason",    e.getRefusedReason());
        m.put("approvedBy",       e.getApprovedBy());
        m.put("approvedAt",       e.getApprovedAt());
        m.put("createdAt",        e.getCreatedAt());
        return m;
    }

    private Map<String, Object> balanceToMap(LeaveBalanceEntity b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("consultantEmail", b.getConsultantEmail());
        m.put("year",            b.getYear());
        m.put("cpInitial",       b.getCpInitial());
        m.put("cpTaken",         b.getCpTaken());
        m.put("cpRemaining",     b.getCpInitial().subtract(b.getCpTaken()));
        m.put("rttInitial",      b.getRttInitial());
        m.put("rttTaken",        b.getRttTaken());
        m.put("rttRemaining",    b.getRttInitial().subtract(b.getRttTaken()));
        return m;
    }
}
