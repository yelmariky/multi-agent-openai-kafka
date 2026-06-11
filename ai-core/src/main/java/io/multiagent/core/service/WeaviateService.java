package io.multiagent.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.multiagent.core.client.LLMAIClient;
import io.multiagent.core.cra.entity.CraEntity;
import io.multiagent.core.cra.repository.CraJpaRepository;
import io.multiagent.core.document.entity.DocumentChunkEntity;
import io.multiagent.core.document.repository.DocumentChunkJpaRepository;
import io.multiagent.core.expense.entity.ExpenseEntity;
import io.multiagent.core.expense.repository.ExpenseJpaRepository;
import io.multiagent.core.infrastructure.tenant.TenantContext;
import io.multiagent.core.model.ConsultantProfile;
import io.multiagent.core.model.CraDayEntry;
import io.multiagent.core.model.CraRequest;
import io.multiagent.core.model.ExpenseItem;
import io.multiagent.core.model.SellerProfile;
import io.multiagent.core.settings.entity.ConsultantProfileEntity;
import io.multiagent.core.settings.entity.SellerProfileEntity;
import io.multiagent.core.organization.repository.MissionRepository;
import io.multiagent.core.settings.repository.ConsultantProfileJpaRepository;
import io.multiagent.core.settings.repository.SellerProfileJpaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Facade service — delegates to JPA/pgvector repositories.
 * Replaces the old Weaviate-backed implementation.
 * Keeps the same public API to minimize changes in consumers.
 */
@Slf4j
@Service
public class WeaviateService {

    private final LLMAIClient llm;
    private final ObjectMapper objectMapper;
    private final ExpenseJpaRepository expenseRepo;
    private final CraJpaRepository craRepo;
    private final SellerProfileJpaRepository sellerProfileRepo;
    private final ConsultantProfileJpaRepository consultantProfileRepo;
    private final DocumentChunkJpaRepository documentChunkRepo;
    private final MissionRepository missionRepo;
    private final io.multiagent.core.organization.repository.ProjectRepository projectRepo;

    public WeaviateService(
            LLMAIClient llm,
            ObjectMapper objectMapper,
            ExpenseJpaRepository expenseRepo,
            CraJpaRepository craRepo,
            SellerProfileJpaRepository sellerProfileRepo,
            ConsultantProfileJpaRepository consultantProfileRepo,
            DocumentChunkJpaRepository documentChunkRepo,
            MissionRepository missionRepo,
            io.multiagent.core.organization.repository.ProjectRepository projectRepo) {
        this.llm = llm;
        this.objectMapper = objectMapper;
        this.expenseRepo = expenseRepo;
        this.craRepo = craRepo;
        this.sellerProfileRepo = sellerProfileRepo;
        this.consultantProfileRepo = consultantProfileRepo;
        this.documentChunkRepo = documentChunkRepo;
        this.missionRepo = missionRepo;
        this.projectRepo = projectRepo;
    }

    // -----------------------------------------------------------------------
    // DocumentChunk — indexation + recherche vectorielle
    // -----------------------------------------------------------------------

    public void indexChunk(String text, String source) {
        indexChunk(null, text, source);
    }

    @Transactional
    public void indexChunk(String id, String text, String source) {
        float[] embedding = llm.embed(text);
        persistChunk(id, text, source, embedding);
    }

    @Transactional
    public void indexChunk(String id, String text, List<Double> vector) {
        float[] arr = new float[vector.size()];
        for (int i = 0; i < vector.size(); i++) arr[i] = vector.get(i).floatValue();
        persistChunk(id, text, null, arr);
    }

    public List<String> searchByVector(List<Double> vector, int k) {
        try {
            UUID tenantId = TenantContext.getTenantId();
            String pgVector = toPgVectorString(vector);
            List<DocumentChunkEntity> results = documentChunkRepo.findSimilar(tenantId, pgVector, k);
            return results.stream()
                    .map(DocumentChunkEntity::getContent)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.error("searchByVector error: {}", e.getMessage(), e);
            return List.of();
        }
    }

    // -----------------------------------------------------------------------
    // Expense
    // -----------------------------------------------------------------------

    @Transactional
    public void indexExpense(String id, ExpenseItem item, String source, boolean duplicate, String hash) {
        try {
            if (item == null || item.getAmount() == null
                    || isNullOrBlank(item.getCurrency())
                    || isNullOrBlank(item.getType())
                    || isNullOrBlank(item.getPaymentMode())
                    || isNullOrBlank(item.getAddress())) {
                log.warn("indexExpense ignored: missing required fields for {}", item);
                return;
            }

            UUID tenantId = TenantContext.getTenantId();
            ExpenseEntity entity = new ExpenseEntity();
            entity.setTenantId(tenantId);
            if (id != null && !id.isBlank()) {
                try { entity.setId(UUID.fromString(id)); } catch (Exception ignored) {}
            }
            entity.setExpenseId(item.getId());
            entity.setAmount(BigDecimal.valueOf(item.getAmount()));
            entity.setCurrency(item.getCurrency());
            entity.setType(item.getType());
            entity.setKm(item.getKm() != null ? BigDecimal.valueOf(item.getKm()) : null);
            if (item.getDate() != null && !item.getDate().isBlank()) {
                try { entity.setExpenseDate(LocalDate.parse(item.getDate())); } catch (Exception ignored) {}
            }
            entity.setDateText(item.getDate());
            entity.setDescription(item.getDescription());
            entity.setOriginalText(item.getOriginalText());
            entity.setSource(source);
            entity.setPaymentMode(item.getPaymentMode());
            entity.setAddress(item.getAddress());
            entity.setCompany(item.getCompany());
            entity.setConsultantEmail(item.getConsultantEmail() != null
                    ? item.getConsultantEmail().toLowerCase(Locale.ROOT) : null);
            entity.setDuplicateFlag(duplicate);
            entity.setHash(hash);
            entity.setApprovalStatus(item.getApprovalStatus() != null ? item.getApprovalStatus() : "PENDING");
            entity.setApprovalNote(item.getApprovalNote());
            if (item.getAbsencePeriods() != null && !item.getAbsencePeriods().isEmpty()) {
                try {
                    entity.setAbsencePeriodsJson(objectMapper.writeValueAsString(item.getAbsencePeriods()));
                } catch (Exception ex) {
                    log.warn("Could not serialize absencePeriods: {}", ex.getMessage());
                }
            }

            expenseRepo.save(entity);
            log.info("Expense indexed (expenseId={})", item.getId());
        } catch (Exception e) {
            log.error("Exception indexExpense: {}", e.getMessage(), e);
        }
    }

    public List<ExpenseItem> findExpensesBetween(LocalDate start, LocalDate end) {
        return findExpensesBetween(start, end, null, null, null);
    }

    public List<ExpenseItem> findExpensesBetween(LocalDate start, LocalDate end, String type, String currency) {
        return findExpensesBetween(start, end, type, currency, null);
    }

    public List<ExpenseItem> findExpensesBetween(LocalDate start, LocalDate end, String type, String currency, String consultantEmail) {
        try {
            UUID tenantId = TenantContext.getTenantId();
            List<ExpenseEntity> entities;

            if (consultantEmail != null && !consultantEmail.isBlank()) {
                entities = expenseRepo.findByTenantIdAndExpenseDateBetweenAndConsultantEmailIgnoreCase(
                        tenantId, start, end, consultantEmail.toLowerCase(Locale.ROOT));
            } else {
                entities = expenseRepo.findByTenantIdAndExpenseDateBetween(tenantId, start, end);
            }

            return entities.stream()
                    .map(this::toExpenseItem)
                    .filter(item -> matchesTypeAndCurrency(item, type, currency))
                    .toList();
        } catch (Exception e) {
            log.error("Exception findExpensesBetween: {}", e.getMessage(), e);
            return List.of();
        }
    }

    public int findMaxExpenseId(YearMonth ym) {
        try {
            UUID tenantId = TenantContext.getTenantId();
            LocalDate start = ym.atDay(1);
            LocalDate end = ym.atEndOfMonth();
            List<ExpenseEntity> entities = expenseRepo.findByTenantIdAndExpenseDateBetween(tenantId, start, end);
            return entities.stream()
                    .filter(e -> e.getExpenseId() != null)
                    .mapToInt(ExpenseEntity::getExpenseId)
                    .max()
                    .orElse(0);
        } catch (Exception e) {
            log.warn("findMaxExpenseId exception: {}", e.getMessage());
            return 0;
        }
    }

    @Transactional
    public int deleteExpensesByDate(LocalDate date) {
        return deleteExpensesByDate(date, null);
    }

    @Transactional
    public int deleteExpensesByDate(LocalDate date, String company) {
        if (date == null) return 0;
        try {
            UUID tenantId = TenantContext.getTenantId();
            List<ExpenseEntity> entities = expenseRepo.findByTenantIdAndExpenseDateBetween(tenantId, date, date);
            if (company != null && !company.isBlank()) {
                entities = entities.stream()
                        .filter(e -> company.trim().equalsIgnoreCase(
                                e.getCompany() != null ? e.getCompany().trim() : ""))
                        .toList();
            }
            expenseRepo.deleteAll(entities);
            log.info("deleteExpensesByDate -> date={}, company={}, deleted={}", date, company, entities.size());
            return entities.size();
        } catch (Exception e) {
            log.error("Exception deleteExpensesByDate: {}", e.getMessage(), e);
            return -1;
        }
    }

    @Transactional
    public int deleteExpenseByIdAndMonth(int expenseId, YearMonth ym) {
        return deleteExpenseByIdAndMonth(expenseId, ym, null);
    }

    @Transactional
    public int deleteExpenseByIdAndMonth(int expenseId, YearMonth ym, String company) {
        try {
            UUID tenantId = TenantContext.getTenantId();
            LocalDate start = ym.atDay(1);
            LocalDate end = ym.atEndOfMonth();
            List<ExpenseEntity> entities = expenseRepo.findByTenantIdAndExpenseDateBetween(tenantId, start, end);
            List<ExpenseEntity> matches = entities.stream()
                    .filter(e -> e.getExpenseId() != null && e.getExpenseId() == expenseId)
                    .filter(e -> matchesCompany(e.getCompany(), company))
                    .toList();
            expenseRepo.deleteAll(matches);
            return matches.size();
        } catch (Exception e) {
            log.error("deleteExpenseByIdAndMonth exception: {}", e.getMessage(), e);
            return -1;
        }
    }

    @Transactional
    public int deleteExpenseById(int expenseId) {
        return deleteExpenseById(expenseId, null);
    }

    @Transactional
    public int deleteExpenseById(int expenseId, String company) {
        try {
            UUID tenantId = TenantContext.getTenantId();
            List<ExpenseEntity> all = expenseRepo.findByTenantIdAndExpenseDateBetween(
                    tenantId, LocalDate.of(2000, 1, 1), LocalDate.of(2100, 1, 1));
            List<ExpenseEntity> matches = all.stream()
                    .filter(e -> e.getExpenseId() != null && e.getExpenseId() == expenseId)
                    .filter(e -> matchesCompany(e.getCompany(), company))
                    .toList();
            expenseRepo.deleteAll(matches);
            return matches.size();
        } catch (Exception e) {
            log.error("deleteExpenseById exception: {}", e.getMessage(), e);
            return -1;
        }
    }

    @Transactional
    public void updateExpenseApproval(String entityId, String status, String note) {
        try {
            UUID id = UUID.fromString(entityId);
            ExpenseEntity entity = expenseRepo.findById(id)
                    .orElseThrow(() -> new RuntimeException("Expense not found: " + entityId));
            entity.setApprovalStatus(status);
            if (note != null) entity.setApprovalNote(note);
            expenseRepo.save(entity);
            log.info("Expense approval updated: id={}, status={}", entityId, status);
        } catch (Exception e) {
            log.error("updateExpenseApproval exception: {}", e.getMessage(), e);
            throw new RuntimeException("updateExpenseApproval failed: " + e.getMessage(), e);
        }
    }

    public List<ExpenseItem.AbsencePeriod> findKmExpenseAbsences(String company, String month) {
        try {
            if (isNullOrBlank(month)) return List.of();
            UUID tenantId = TenantContext.getTenantId();
            YearMonth ym = YearMonth.parse(month);
            List<ExpenseEntity> entities = expenseRepo.findByTenantIdAndExpenseDateBetween(
                    tenantId, ym.atDay(1), ym.atEndOfMonth());

            List<ExpenseItem.AbsencePeriod> result = new ArrayList<>();
            for (ExpenseEntity e : entities) {
                String type = e.getType() != null ? e.getType().toLowerCase(Locale.ROOT) : "";
                if (!type.contains("km") && !type.contains("kilom")) continue;
                if (!isNullOrBlank(company) && !company.trim().equalsIgnoreCase(
                        e.getCompany() != null ? e.getCompany().trim() : "")) continue;

                if (e.getAbsencePeriodsJson() != null && !e.getAbsencePeriodsJson().isBlank()) {
                    try {
                        List<ExpenseItem.AbsencePeriod> periods = objectMapper.readValue(
                                e.getAbsencePeriodsJson(),
                                objectMapper.getTypeFactory().constructCollectionType(
                                        List.class, ExpenseItem.AbsencePeriod.class));
                        result.addAll(periods);
                    } catch (Exception ex) {
                        log.warn("Could not parse absencePeriodsJson: {}", ex.getMessage());
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.error("findKmExpenseAbsences exception: {}", e.getMessage(), e);
            return List.of();
        }
    }

    // -----------------------------------------------------------------------
    // CRA
    // -----------------------------------------------------------------------

    @Transactional
    public String indexCra(CraRequest cra) {
        try {
            UUID tenantId = TenantContext.getTenantId();
            CraEntity entity;

            if (cra.id() != null && !cra.id().isBlank()) {
                UUID existingId = UUID.fromString(cra.id());
                entity = craRepo.findById(existingId).orElse(new CraEntity());
                entity.setId(existingId);
            } else {
                // Upsert par tuple — évite les doublons si l'id est perdu côté client
                entity = craRepo.findByTenantIdAndConsultantIgnoreCaseAndBillingMonth(
                                tenantId, cra.consultant(), cra.billingMonth())
                        .orElse(new CraEntity());
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

            if (cra.missionId() != null && !cra.missionId().isBlank()) {
                entity.setMissionId(UUID.fromString(cra.missionId()));
            }

            if (cra.projectId() != null && !cra.projectId().isBlank()) {
                entity.setProjectId(UUID.fromString(cra.projectId()));
            } else {
                entity.setProjectId(null);
            }

            if (cra.entries() != null) {
                try {
                    entity.setEntriesJson(objectMapper.writeValueAsString(cra.entries()));
                } catch (Exception e) {
                    log.warn("Could not serialize CRA entries: {}", e.getMessage());
                    entity.setEntriesJson("[]");
                }
            } else {
                entity.setEntriesJson("[]");
            }

            CraEntity saved = craRepo.save(entity);
            log.info("CRA upserted (uuid={}, consultant={}, month={})",
                    saved.getId(), cra.consultant(), cra.billingMonth());
            return saved.getId().toString();
        } catch (Exception e) {
            log.error("indexCra exception: {}", e.getMessage(), e);
            throw new RuntimeException("indexCra exception: " + e.getMessage(), e);
        }
    }

    public List<Map<String, Object>> findCrasByPeriod(String start, String end, String consultant, String company) {
        try {
            UUID tenantId = TenantContext.getTenantId();
            List<CraEntity> entities;

            if (!isNullOrBlank(consultant)) {
                entities = craRepo.findByTenantIdAndConsultantContainingIgnoreCase(tenantId, consultant);
            } else {
                entities = craRepo.findByTenantId(tenantId);
            }

            return entities.stream()
                    .filter(e -> {
                        String bm = e.getBillingMonth() != null ? e.getBillingMonth() : "";
                        if (!isNullOrBlank(start) && bm.compareTo(start) < 0) return false;
                        if (!isNullOrBlank(end) && bm.compareTo(end) > 0) return false;
                        return true;
                    })
                    .filter(e -> isNullOrBlank(company) || company.trim().equalsIgnoreCase(
                            e.getCompany() != null ? e.getCompany().trim() : ""))
                    .map(this::toCraMap)
                    .sorted(Comparator.comparing(m -> (String) m.getOrDefault("billingMonth", ""),
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (Exception e) {
            log.error("findCrasByPeriod exception: {}", e.getMessage(), e);
            return List.of();
        }
    }

    public List<ExpenseItem.AbsencePeriod> findCraAbsentDays(String consultant, String company, String month) {
        try {
            if (isNullOrBlank(consultant) || isNullOrBlank(month)) return List.of();

            List<Map<String, Object>> cras = findCrasByPeriod(month, month, consultant, company);
            List<ExpenseItem.AbsencePeriod> result = new ArrayList<>();
            for (Map<String, Object> cra : cras) {
                Object entriesObj = cra.get("entries");
                if (!(entriesObj instanceof List<?> entriesList)) continue;
                for (Object entry : entriesList) {
                    if (!(entry instanceof CraDayEntry dayEntry)) continue;
                    if ("ABSENT".equalsIgnoreCase(dayEntry.type())) {
                        String d = dayEntry.date();
                        if (d != null && !d.isBlank()) {
                            result.add(new ExpenseItem.AbsencePeriod(d, d));
                        }
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.error("findCraAbsentDays exception: {}", e.getMessage(), e);
            return List.of();
        }
    }

    @Transactional
    public void deleteCra(String id) {
        try {
            if (isNullOrBlank(id)) return;
            craRepo.deleteById(UUID.fromString(id));
            log.info("CRA deleted (id={})", id);
        } catch (Exception e) {
            log.error("deleteCra exception: {}", e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Settings (SellerProfile / ConsultantProfile)
    // -----------------------------------------------------------------------

    public SellerProfile findSellerProfile(String companyName) {
        if (isNullOrBlank(companyName)) return null;
        try {
            UUID tenantId = TenantContext.getTenantId();
            return sellerProfileRepo.findByTenantId(tenantId)
                    .map(this::toSellerProfile)
                    .orElse(null);
        } catch (Exception e) {
            log.error("findSellerProfile exception: {}", e.getMessage(), e);
            return null;
        }
    }

    @Transactional
    public void upsertSellerProfile(SellerProfile profile) {
        try {
            UUID tenantId = TenantContext.getTenantId();
            SellerProfileEntity entity = sellerProfileRepo.findByTenantId(tenantId)
                    .orElse(new SellerProfileEntity());
            entity.setTenantId(tenantId);
            entity.setCompanyName(profile.companyName() != null ? profile.companyName() : "");
            entity.setAddress(profile.address());
            entity.setRcs(profile.rcs());
            entity.setIban(profile.iban());
            entity.setBic(profile.bic());
            entity.setEmail(profile.email());
            entity.setCapital(profile.capital());
            entity.setLatePaymentClause(profile.latePaymentClause());
            sellerProfileRepo.save(entity);
            log.info("SellerProfile upserted (companyName={})", profile.companyName());
        } catch (Exception e) {
            log.error("upsertSellerProfile exception: {}", e.getMessage(), e);
            throw new RuntimeException("upsertSellerProfile failed: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void upsertConsultantProfile(ConsultantProfile profile) {
        try {
            UUID tenantId = TenantContext.getTenantId();
            String email = profile.email() != null ? profile.email().toLowerCase(Locale.ROOT).trim() : "";
            ConsultantProfileEntity entity = consultantProfileRepo
                    .findByTenantIdAndEmailIgnoreCase(tenantId, email)
                    .orElse(new ConsultantProfileEntity());
            entity.setTenantId(tenantId);
            entity.setEmail(email);
            entity.setName(profile.name());
            entity.setRole(profile.role());
            entity.setCompany(profile.company());
            entity.setClientName(profile.clientName());
            entity.setClientAddress(profile.clientAddress());
            entity.setClientRcs(profile.clientRcs());
            entity.setClientContactEmail(profile.clientContactEmail());
            entity.setTjm(profile.tjm() != null ? BigDecimal.valueOf(profile.tjm()) : null);
            entity.setActive(profile.active() != null ? profile.active() : true);
            consultantProfileRepo.save(entity);
            log.info("ConsultantProfile upserted (email={})", email);
        } catch (Exception e) {
            log.error("upsertConsultantProfile exception: {}", e.getMessage(), e);
            throw new RuntimeException("upsertConsultantProfile failed: " + e.getMessage(), e);
        }
    }

    public List<ConsultantProfile> findAllConsultantProfiles(String company) {
        try {
            UUID tenantId = TenantContext.getTenantId();
            List<ConsultantProfileEntity> entities = consultantProfileRepo.findByTenantId(tenantId);
            return entities.stream()
                    .map(this::toConsultantProfile)
                    .filter(p -> isNullOrBlank(company) || company.trim().equalsIgnoreCase(
                            p.company() != null ? p.company().trim() : ""))
                    .toList();
        } catch (Exception e) {
            log.error("findAllConsultantProfiles exception: {}", e.getMessage(), e);
            return List.of();
        }
    }

    @Transactional
    public void deleteConsultantProfile(String email) {
        try {
            UUID tenantId = TenantContext.getTenantId();
            consultantProfileRepo.deleteByTenantIdAndEmailIgnoreCase(tenantId, email);
            log.info("ConsultantProfile deleted (email={})", email);
        } catch (Exception e) {
            log.error("deleteConsultantProfile exception: {}", e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Private mapping helpers
    // -----------------------------------------------------------------------

    private void persistChunk(String id, String text, String source, float[] embedding) {
        UUID tenantId = TenantContext.getTenantId();
        DocumentChunkEntity entity = new DocumentChunkEntity();
        if (id != null && !id.isBlank()) {
            try { entity.setId(UUID.fromString(id)); } catch (Exception ignored) {}
        }
        entity.setTenantId(tenantId);
        entity.setContent(text);
        entity.setSource(source);
        // Note: embedding stored via native SQL since JPA doesn't natively handle pgvector
        documentChunkRepo.save(entity);
    }

    private ExpenseItem toExpenseItem(ExpenseEntity e) {
        ExpenseItem item = new ExpenseItem();
        item.setId(e.getExpenseId());
        item.setAmount(e.getAmount() != null ? e.getAmount().doubleValue() : null);
        item.setCurrency(e.getCurrency());
        item.setType(e.getType());
        item.setKm(e.getKm() != null ? e.getKm().doubleValue() : null);
        item.setDate(e.getExpenseDate() != null ? e.getExpenseDate().toString() : e.getDateText());
        item.setDescription(e.getDescription());
        item.setOriginalText(e.getOriginalText());
        item.setPaymentMode(e.getPaymentMode());
        item.setAddress(e.getAddress());
        item.setCompany(e.getCompany());
        item.setConsultantEmail(e.getConsultantEmail());
        item.setApprovalStatus(e.getApprovalStatus());
        item.setApprovalNote(e.getApprovalNote());
        item.setWeaviateId(e.getId().toString());
        item.setStatus(e.getApprovalStatus());
        if (e.getDuplicateFlag() != null && e.getDuplicateFlag()) {
            item.setMonthly(false);
        }
        if (e.getAbsencePeriodsJson() != null && !e.getAbsencePeriodsJson().isBlank()) {
            try {
                item.setAbsencePeriods(objectMapper.readValue(e.getAbsencePeriodsJson(),
                        objectMapper.getTypeFactory().constructCollectionType(
                                List.class, ExpenseItem.AbsencePeriod.class)));
            } catch (Exception ignored) {}
        }
        return item;
    }

    private Map<String, Object> toCraMap(CraEntity e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", e.getId().toString());
        row.put("consultant", e.getConsultant() != null ? e.getConsultant() : "");
        row.put("company", e.getCompany() != null ? e.getCompany() : "");
        row.put("clientCompany", e.getClientCompany() != null ? e.getClientCompany() : "");
        row.put("clientContactEmail", e.getClientContactEmail() != null ? e.getClientContactEmail() : "");
        row.put("billingMonth", e.getBillingMonth() != null ? e.getBillingMonth() : "");
        row.put("totalDays", e.getTotalDays() != null ? e.getTotalDays().doubleValue() : 0.0);
        row.put("status", e.getStatus() != null ? e.getStatus() : "");
        row.put("submittedAt", e.getSubmittedAt() != null ? e.getSubmittedAt() : "");
        row.put("validatedAt", e.getValidatedAt() != null ? e.getValidatedAt() : "");
        row.put("validatedBy", e.getValidatedBy() != null ? e.getValidatedBy() : "");
        row.put("refusedReason", e.getRefusedReason() != null ? e.getRefusedReason() : "");
        row.put("missionId", e.getMissionId() != null ? e.getMissionId().toString() : "");
        if (e.getMissionId() != null) {
            missionRepo.findById(e.getMissionId()).ifPresent(m ->
                    row.put("missionTitle", m.getTitle()));
        }
        if (!row.containsKey("missionTitle")) row.put("missionTitle", "");

        row.put("projectId", e.getProjectId() != null ? e.getProjectId().toString() : "");
        if (e.getProjectId() != null) {
            projectRepo.findById(e.getProjectId()).ifPresent(p -> {
                row.put("projectName", p.getName());
                row.put("projectDescription", p.getDescription() != null ? p.getDescription() : "");
            });
        }
        if (!row.containsKey("projectName")) row.put("projectName", "");
        if (!row.containsKey("projectDescription")) row.put("projectDescription", "");

        String entriesJson = e.getEntriesJson() != null ? e.getEntriesJson() : "[]";
        row.put("entriesJson", entriesJson);
        List<CraDayEntry> entries = List.of();
        if (!entriesJson.isBlank() && !"[]".equals(entriesJson)) {
            try {
                entries = objectMapper.readValue(entriesJson,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, CraDayEntry.class));
            } catch (Exception ex) {
                log.warn("Could not parse CRA entriesJson: {}", ex.getMessage());
            }
        }
        row.put("entries", entries);

        double total = e.getTotalDays() != null ? e.getTotalDays().doubleValue() : 0.0;
        if (total <= 0.0 && !entries.isEmpty()) {
            total = entries.stream()
                    .filter(entry -> entry != null && entry.value() > 0)
                    .mapToDouble(CraDayEntry::value)
                    .sum();
            row.put("totalDays", total);
        }

        return row;
    }

    private SellerProfile toSellerProfile(SellerProfileEntity e) {
        return new SellerProfile(
                e.getCompanyName(), e.getAddress(), e.getRcs(),
                e.getIban(), e.getBic(), e.getEmail(),
                e.getCapital(), e.getLatePaymentClause()
        );
    }

    private ConsultantProfile toConsultantProfile(ConsultantProfileEntity e) {
        return new ConsultantProfile(
                e.getId() != null ? e.getId().toString() : null,
                e.getEmail(), e.getName(), e.getRole(), e.getCompany(),
                e.getClientName(), e.getClientAddress(), e.getClientRcs(),
                e.getClientContactEmail(),
                e.getTjm() != null ? e.getTjm().doubleValue() : null,
                e.getActive()
        );
    }

    private boolean matchesTypeAndCurrency(ExpenseItem item, String type, String currency) {
        if (type != null && !type.isBlank() && !type.equalsIgnoreCase(item.getType())) return false;
        if (currency != null && !currency.isBlank() && !currency.equalsIgnoreCase(item.getCurrency())) return false;
        return true;
    }

    private boolean matchesCompany(String stored, String expected) {
        if (expected == null || expected.isBlank()) return true;
        if (stored == null) return false;
        return stored.trim().equalsIgnoreCase(expected.trim());
    }

    private String toPgVectorString(List<Double> vector) {
        return "[" + vector.stream().map(String::valueOf).collect(Collectors.joining(",")) + "]";
    }

    private boolean isNullOrBlank(String s) {
        return s == null || s.isBlank();
    }
}
