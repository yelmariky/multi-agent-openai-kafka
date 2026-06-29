package io.multiagent.activity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.multiagent.activity.cra.entity.CraEntity;
import io.multiagent.activity.cra.repository.CraJpaRepository;
import io.multiagent.activity.infrastructure.tenant.TenantContext;
import io.multiagent.activity.model.ConsultantProfile;
import io.multiagent.activity.model.CraDayEntry;
import io.multiagent.activity.model.CraRequest;
import io.multiagent.activity.model.ExpenseItem;
import io.multiagent.activity.model.VehicleType;
import io.multiagent.activity.organization.entity.ProjectEntity;
import io.multiagent.activity.organization.repository.ProjectRepository;
import io.multiagent.activity.settings.entity.ConsultantProfileEntity;
import io.multiagent.activity.settings.repository.ConsultantProfileJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service de données pour CRA et congés.
 * Extrait de WeaviateService lors de la décomposition en microservices.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityDataService {

    private final ObjectMapper              objectMapper;
    private final CraJpaRepository          craRepo;
    private final ConsultantProfileJpaRepository consultantProfileRepo;
    private final ProjectRepository         projectRepo;
    private final io.multiagent.activity.settings.repository.SellerProfileJpaRepository sellerProfileRepo;

    // ── CRA ───────────────────────────────────────────────────────────────────

    @Transactional
    public String indexCra(CraRequest cra) {
        try {
            UUID tenantId = TenantContext.getTenantId();
            CraEntity entity;
            if (cra.id() != null && !cra.id().isBlank()) {
                entity = craRepo.findById(UUID.fromString(cra.id())).orElse(new CraEntity());
                entity.setId(UUID.fromString(cra.id()));
            } else {
                entity = craRepo.findByTenantIdAndConsultantIgnoreCaseAndBillingMonth(
                        tenantId, cra.consultant(), cra.billingMonth()).orElse(new CraEntity());
            }
            entity.setTenantId(tenantId);
            entity.setConsultant(cra.consultant() != null ? cra.consultant() : "");
            entity.setCompany(cra.company() != null ? cra.company() : "");
            entity.setClientCompany(cra.clientCompany() != null ? cra.clientCompany() : "");
            entity.setClientContactEmail(cra.clientContactEmail() != null ? cra.clientContactEmail() : "");
            entity.setBillingMonth(cra.billingMonth() != null ? cra.billingMonth() : "");
            entity.setTotalDays(BigDecimal.valueOf(cra.totalDays()));
            entity.setStatus(cra.status() != null ? cra.status() : "BROUILLON");
            entity.setSubmittedAt(cra.submittedAt() != null ? cra.submittedAt() : "");
            entity.setValidatedAt(cra.validatedAt() != null ? cra.validatedAt() : "");
            entity.setValidatedBy(cra.validatedBy() != null ? cra.validatedBy() : "");
            entity.setRefusedReason(cra.refusedReason() != null ? cra.refusedReason() : "");
            if (cra.missionId() != null && !cra.missionId().isBlank())
                entity.setMissionId(UUID.fromString(cra.missionId()));
            if (cra.projectId() != null && !cra.projectId().isBlank())
                entity.setProjectId(UUID.fromString(cra.projectId()));
            else entity.setProjectId(null);
            if (cra.clientValidationRef() != null && !cra.clientValidationRef().isBlank())
                entity.setClientValidationRef(cra.clientValidationRef());
            if (cra.clientValidationDate() != null && !cra.clientValidationDate().isBlank()) {
                try { entity.setClientValidationDate(LocalDate.parse(cra.clientValidationDate())); } catch (Exception ignored) {}
            }
            if (cra.entries() != null) {
                try { entity.setEntriesJson(objectMapper.writeValueAsString(cra.entries())); }
                catch (Exception e) { log.warn("Could not serialize CRA entries: {}", e.getMessage()); entity.setEntriesJson("[]"); }
            } else { entity.setEntriesJson("[]"); }

            CraEntity saved = craRepo.save(entity);
            log.info("CRA upserted (uuid={}, consultant={}, month={})", saved.getId(), cra.consultant(), cra.billingMonth());
            return saved.getId().toString();
        } catch (Exception e) {
            log.error("indexCra exception: {}", e.getMessage(), e);
            throw new RuntimeException("indexCra exception: " + e.getMessage(), e);
        }
    }

    public List<Map<String, Object>> findCrasByPeriod(String start, String end, String consultant, String company) {
        try {
            UUID tenantId = TenantContext.getTenantId();
            List<CraEntity> entities = isNullOrBlank(consultant)
                    ? craRepo.findByTenantId(tenantId)
                    : craRepo.findByTenantIdAndConsultantContainingIgnoreCase(tenantId, consultant);
            return entities.stream()
                    .filter(e -> {
                        String bm = e.getBillingMonth() != null ? e.getBillingMonth() : "";
                        if (!isNullOrBlank(start) && bm.compareTo(start) < 0) return false;
                        if (!isNullOrBlank(end)   && bm.compareTo(end)   > 0) return false;
                        return true;
                    })
                    .filter(e -> isNullOrBlank(company) || company.trim().equalsIgnoreCase(
                            e.getCompany() != null ? e.getCompany().trim() : ""))
                    .map(this::toCraMap)
                    .sorted(Comparator.comparing(m -> (String) m.getOrDefault("billingMonth", ""),
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (Exception e) { log.error("findCrasByPeriod exception: {}", e.getMessage(), e); return List.of(); }
    }

    public List<ExpenseItem.AbsencePeriod> findCraAbsentDays(String consultant, String company, String month) {
        try {
            if (isNullOrBlank(consultant) || isNullOrBlank(month)) return List.of();
            List<ExpenseItem.AbsencePeriod> result = new ArrayList<>();
            for (Map<String, Object> cra : findCrasByPeriod(month, month, consultant, company)) {
                Object entriesObj = cra.get("entries");
                if (!(entriesObj instanceof List<?> entriesList)) continue;
                for (Object entry : entriesList) {
                    if (!(entry instanceof CraDayEntry dayEntry)) continue;
                    if ("ABSENT".equalsIgnoreCase(dayEntry.type()) && dayEntry.date() != null)
                        result.add(new ExpenseItem.AbsencePeriod(dayEntry.date(), dayEntry.date()));
                }
            }
            return result;
        } catch (Exception e) { return List.of(); }
    }

    @Transactional
    public void deleteCra(String id) {
        try {
            if (isNullOrBlank(id)) return;
            craRepo.deleteById(UUID.fromString(id));
            log.info("CRA deleted (id={})", id);
        } catch (Exception e) { log.error("deleteCra exception: {}", e.getMessage(), e); }
    }

    // ── Congés → CRA ──────────────────────────────────────────────────────────

    @Transactional
    public void applyPendingLeaveAbsences(String consultant, LocalDate start, LocalDate end, UUID tenantId) {
        applyLeaveEntries(consultant, start, end, tenantId, "__LEAVE_PENDING__", false);
    }

    @Transactional
    public void applyLeaveAbsences(String consultant, UUID leaveId, LocalDate start, LocalDate end, UUID tenantId) {
        applyLeaveEntries(consultant, start, end, tenantId, "__LEAVE__:" + leaveId, true);
    }

    @Transactional
    public void removeLeaveAbsences(String consultant, LocalDate start, LocalDate end, UUID tenantId) {
        Map<YearMonth, List<LocalDate>> byMonth = buildByMonth(start, end);
        for (Map.Entry<YearMonth, List<LocalDate>> entry : byMonth.entrySet()) {
            String month = entry.getKey().toString();
            Set<String> dates = entry.getValue().stream().map(LocalDate::toString).collect(Collectors.toSet());
            craRepo.findByTenantIdAndConsultantIgnoreCaseAndBillingMonth(tenantId, consultant, month)
                    .ifPresent(cra -> {
                        List<CraDayEntry> entries = parseEntriesJson(cra.getEntriesJson());
                        entries.removeIf(e -> dates.contains(e.date()) && "ABSENT".equals(e.type()));
                        cra.setEntriesJson(serializeEntries(entries));
                        craRepo.save(cra);
                    });
        }
    }

    // ── Profil consultant ─────────────────────────────────────────────────────

    public Optional<ConsultantProfileEntity> findConsultantProfileEntity(UUID tenantId, String email) {
        return consultantProfileRepo.findByTenantIdAndEmailIgnoreCase(tenantId, email);
    }

    // ── Helpers privés ────────────────────────────────────────────────────────

    private void applyLeaveEntries(String consultant, LocalDate start, LocalDate end,
                                   UUID tenantId, String tag, boolean clearTravail) {
        Map<YearMonth, List<LocalDate>> byMonth = buildByMonth(start, end);
        for (Map.Entry<YearMonth, List<LocalDate>> entry : byMonth.entrySet()) {
            String month = entry.getKey().toString();
            List<LocalDate> days = entry.getValue();
            Set<String> leaveDates = days.stream().map(LocalDate::toString).collect(Collectors.toSet());

            CraEntity cra = craRepo.findByTenantIdAndConsultantIgnoreCaseAndBillingMonth(tenantId, consultant, month)
                    .orElseGet(() -> {
                        CraEntity c = new CraEntity();
                        c.setTenantId(tenantId);
                        c.setConsultant(consultant);
                        c.setBillingMonth(month);
                        c.setStatus("BROUILLON");
                        c.setEntriesJson("[]");
                        return c;
                    });

            List<CraDayEntry> entries = parseEntriesJson(cra.getEntriesJson());
            String leaveTag = tag.startsWith("__LEAVE__:") ? "__LEAVE__" : tag;
            UUID leaveId = tag.startsWith("__LEAVE__:") ? UUID.fromString(tag.substring(10)) : null;

            entries.removeIf(e -> leaveDates.contains(e.date()) && (
                    leaveTag.equals(e.projectId()) || "__LEAVE_PENDING__".equals(e.projectId())
                    || (clearTravail && "TRAVAIL".equals(e.type()))
                    || (clearTravail && "ABSENT".equals(e.type()) && "__ABSENCE__".equals(e.projectId()))
            ));
            for (LocalDate d : days) {
                entries.add(new CraDayEntry(d.toString(), 1.0, "ABSENT",
                        leaveId != null ? "__LEAVE__" : "__LEAVE_PENDING__"));
            }
            cra.setEntriesJson(serializeEntries(entries));
            if (clearTravail && "SOUMIS".equals(cra.getStatus())) cra.setStatus("BROUILLON");
            craRepo.save(cra);
        }
    }

    private Map<YearMonth, List<LocalDate>> buildByMonth(LocalDate start, LocalDate end) {
        Map<YearMonth, List<LocalDate>> byMonth = new TreeMap<>();
        LocalDate d = start;
        while (!d.isAfter(end)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY)
                byMonth.computeIfAbsent(YearMonth.from(d), k -> new ArrayList<>()).add(d);
            d = d.plusDays(1);
        }
        return byMonth;
    }

    private Map<String, Object> toCraMap(CraEntity e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", e.getId().toString());
        row.put("consultant",       e.getConsultant() != null ? e.getConsultant() : "");
        row.put("company",          e.getCompany() != null ? e.getCompany() : "");
        row.put("clientCompany",    e.getClientCompany() != null ? e.getClientCompany() : "");
        row.put("clientContactEmail", e.getClientContactEmail() != null ? e.getClientContactEmail() : "");
        row.put("billingMonth",     e.getBillingMonth() != null ? e.getBillingMonth() : "");
        row.put("totalDays",        e.getTotalDays() != null ? e.getTotalDays().doubleValue() : 0.0);
        row.put("status",           e.getStatus() != null ? e.getStatus() : "");
        row.put("submittedAt",      e.getSubmittedAt() != null ? e.getSubmittedAt() : "");
        row.put("validatedAt",      e.getValidatedAt() != null ? e.getValidatedAt() : "");
        row.put("validatedBy",      e.getValidatedBy() != null ? e.getValidatedBy() : "");
        row.put("refusedReason",    e.getRefusedReason() != null ? e.getRefusedReason() : "");
        row.put("projectId",        e.getProjectId() != null ? e.getProjectId().toString() : "");
        row.put("clientValidationRef",  e.getClientValidationRef() != null ? e.getClientValidationRef() : "");
        row.put("clientValidationDate", e.getClientValidationDate() != null ? e.getClientValidationDate().toString() : "");

        // Project name
        if (e.getProjectId() != null) {
            projectRepo.findById(e.getProjectId()).ifPresent(p -> row.put("projectName", p.getName()));
        }
        if (!row.containsKey("projectName")) row.put("projectName", "");

        String entriesJson = e.getEntriesJson() != null ? e.getEntriesJson() : "[]";
        row.put("entriesJson", entriesJson);
        List<CraDayEntry> entries = parseEntriesJson(entriesJson);
        row.put("entries", entries);

        // Projects list (multi-client PDF)
        List<Map<String, String>> projects = entries.stream()
                .filter(entry -> "TRAVAIL".equals(entry.type()) && entry.projectId() != null && !entry.projectId().isBlank())
                .map(CraDayEntry::projectId).distinct()
                .map(pid -> {
                    Map<String, String> proj = new LinkedHashMap<>();
                    proj.put("id", pid);
                    try { projectRepo.findById(UUID.fromString(pid)).ifPresent(p -> proj.put("name", p.getName() != null ? p.getName() : pid)); }
                    catch (IllegalArgumentException ex) { log.debug("UUID projet invalide : {}", pid); }
                    if (!proj.containsKey("name")) proj.put("name", pid.length() > 8 ? pid.substring(0, 8) + "…" : pid);
                    return proj;
                }).collect(Collectors.toList());
        row.put("projects", projects);

        double total = e.getTotalDays() != null ? e.getTotalDays().doubleValue() : 0.0;
        if (total <= 0.0 && !entries.isEmpty()) {
            total = entries.stream().filter(en -> en != null && en.value() > 0).mapToDouble(CraDayEntry::value).sum();
            row.put("totalDays", total);
        }
        return row;
    }

    private List<CraDayEntry> parseEntriesJson(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) return new ArrayList<>();
        try {
            return new ArrayList<>(objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, CraDayEntry.class)));
        } catch (Exception e) { log.warn("parseEntriesJson failed: {}", e.getMessage()); return new ArrayList<>(); }
    }

    private String serializeEntries(List<CraDayEntry> entries) {
        try { return objectMapper.writeValueAsString(entries); } catch (Exception e) { return "[]"; }
    }

    private boolean isNullOrBlank(String s) { return s == null || s.isBlank(); }

    // ── SellerProfile (pour PDF CRA) ──────────────────────────────────────────

    public io.multiagent.activity.model.SellerProfile findSellerProfile(String companyName) {
        if (isNullOrBlank(companyName)) return null;
        return sellerProfileRepo.findByTenantIdAndCompanyNameIgnoreCase(io.multiagent.activity.infrastructure.tenant.TenantContext.getTenantIdOrNull(), companyName)
                .map(e -> new io.multiagent.activity.model.SellerProfile(
                        e.getCompanyName(), e.getAddress(), e.getRcs(),
                        e.getIban(), e.getBic(), e.getEmail(),
                        e.getCapital(), e.getLatePaymentClause()))
                .orElse(null);
    }

    // ── Stub : km absences depuis expense-service (via Kafka en Phase 3) ──────

    public java.util.List<io.multiagent.activity.model.ExpenseItem.AbsencePeriod> findKmExpenseAbsences(String company, String month) {
        return java.util.List.of();
    }
}
