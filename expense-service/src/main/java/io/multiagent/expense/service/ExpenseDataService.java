package io.multiagent.expense.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.multiagent.expense.client.LLMAIClient;
import io.multiagent.expense.document.entity.DocumentChunkEntity;
import io.multiagent.expense.document.repository.DocumentChunkJpaRepository;
import io.multiagent.expense.entity.ExpenseEntity;
import io.multiagent.expense.infrastructure.tenant.TenantContext;
import io.multiagent.expense.model.ConsultantProfile;
import io.multiagent.expense.model.ExpenseItem;
import io.multiagent.expense.model.VehicleType;
import io.multiagent.expense.repository.ExpenseJpaRepository;
import io.multiagent.expense.settings.entity.ConsultantProfileEntity;
import io.multiagent.expense.settings.repository.ConsultantProfileJpaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service de données pour les notes de frais.
 * Gère l'indexation, la recherche et la mise à jour des dépenses + embeddings RAG.
 * Extrait de WeaviateService lors de la décomposition en microservices.
 */
@Slf4j
@Service
public class ExpenseDataService {

    private final LLMAIClient llm;
    private final ObjectMapper objectMapper;
    private final ExpenseJpaRepository expenseRepo;
    private final ConsultantProfileJpaRepository consultantProfileRepo;
    private final DocumentChunkJpaRepository documentChunkRepo;

    @Value("${expense.rag.min-similarity:0.70}")
    private double ragMinSimilarity;

    @Value("${expense.km-annual:4999}")
    private int kmAnnual;

    public ExpenseDataService(LLMAIClient llm, ObjectMapper objectMapper,
                              ExpenseJpaRepository expenseRepo,
                              ConsultantProfileJpaRepository consultantProfileRepo,
                              DocumentChunkJpaRepository documentChunkRepo) {
        this.llm = llm;
        this.objectMapper = objectMapper;
        this.expenseRepo = expenseRepo;
        this.consultantProfileRepo = consultantProfileRepo;
        this.documentChunkRepo = documentChunkRepo;
    }

    // ── Document chunks / RAG ─────────────────────────────────────────────────

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
            List<DocumentChunkEntity> results =
                    documentChunkRepo.findSimilarAboveThreshold(tenantId, pgVector, k, ragMinSimilarity);
            if (results.isEmpty()) {
                log.info("🛡️ [RAG] Aucun chunk avec similarité ≥ {} — contexte vide retourné", ragMinSimilarity);
            }
            return results.stream().map(DocumentChunkEntity::getContent)
                    .filter(Objects::nonNull).toList();
        } catch (Exception e) {
            log.error("searchByVector error: {}", e.getMessage(), e);
            return List.of();
        }
    }

    // ── Expenses ──────────────────────────────────────────────────────────────

    @Transactional
    public void indexExpense(String id, ExpenseItem item, String source, boolean duplicate, String hash) {
        try {
            if (item == null || item.getAmount() == null
                    || isNullOrBlank(item.getCurrency()) || isNullOrBlank(item.getType())
                    || isNullOrBlank(item.getPaymentMode()) || isNullOrBlank(item.getAddress())) {
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
            if (item.getDate() != null && !item.getDate().isBlank())
                entity.setExpenseDate(parseFlexibleDate(item.getDate()));
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
                } catch (Exception ex) { log.warn("Could not serialize absencePeriods: {}", ex.getMessage()); }
            }
            expenseRepo.save(entity);
            log.info("Expense indexed (expenseId={})", item.getId());
        } catch (Exception e) {
            log.error("Exception indexExpense: {}", e.getMessage(), e);
        }
    }

    public List<ExpenseItem> findExpensesBetween(LocalDate start, LocalDate end) { return findExpensesBetween(start, end, null, null, null); }
    public List<ExpenseItem> findExpensesBetween(LocalDate start, LocalDate end, String type, String currency) { return findExpensesBetween(start, end, type, currency, null); }
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
            return entities.stream().map(this::toExpenseItem)
                    .filter(item -> matchesTypeAndCurrency(item, type, currency)).toList();
        } catch (Exception e) {
            log.error("Exception findExpensesBetween: {}", e.getMessage(), e);
            return List.of();
        }
    }

    public int findMaxExpenseId(YearMonth ym) {
        try {
            UUID tenantId = TenantContext.getTenantId();
            return expenseRepo.findByTenantIdAndExpenseDateBetween(tenantId, ym.atDay(1), ym.atEndOfMonth())
                    .stream().filter(e -> e.getExpenseId() != null)
                    .mapToInt(ExpenseEntity::getExpenseId).max().orElse(0);
        } catch (Exception e) { return 0; }
    }

    @Transactional
    public int deleteExpensesByDate(LocalDate date, String company) {
        if (date == null) return 0;
        try {
            UUID tenantId = TenantContext.getTenantId();
            List<ExpenseEntity> entities = expenseRepo.findByTenantIdAndExpenseDateBetween(tenantId, date, date);
            if (company != null && !company.isBlank())
                entities = entities.stream().filter(e -> company.trim().equalsIgnoreCase(
                        e.getCompany() != null ? e.getCompany().trim() : "")).toList();
            expenseRepo.deleteAll(entities);
            return entities.size();
        } catch (Exception e) { log.error("Exception deleteExpensesByDate: {}", e.getMessage(), e); return -1; }
    }

    @Transactional
    public int deleteExpenseByIdAndMonth(int expenseId, YearMonth ym, String company) {
        try {
            UUID tenantId = TenantContext.getTenantId();
            List<ExpenseEntity> matches = expenseRepo.findByTenantIdAndExpenseDateBetween(
                    tenantId, ym.atDay(1), ym.atEndOfMonth()).stream()
                    .filter(e -> e.getExpenseId() != null && e.getExpenseId() == expenseId)
                    .filter(e -> matchesCompany(e.getCompany(), company)).toList();
            expenseRepo.deleteAll(matches);
            return matches.size();
        } catch (Exception e) { log.error("deleteExpenseByIdAndMonth exception: {}", e.getMessage(), e); return -1; }
    }

    @Transactional
    public int deleteExpenseById(int expenseId, String company) {
        try {
            UUID tenantId = TenantContext.getTenantId();
            List<ExpenseEntity> matches = expenseRepo.findByTenantIdAndExpenseDateBetween(
                    tenantId, LocalDate.of(2000,1,1), LocalDate.of(2100,1,1)).stream()
                    .filter(e -> e.getExpenseId() != null && e.getExpenseId() == expenseId)
                    .filter(e -> matchesCompany(e.getCompany(), company)).toList();
            expenseRepo.deleteAll(matches);
            return matches.size();
        } catch (Exception e) { log.error("deleteExpenseById exception: {}", e.getMessage(), e); return -1; }
    }

    @Transactional
    public ExpenseEntity updateExpenseApproval(String entityId, String status, String note) {
        UUID id = UUID.fromString(entityId);
        ExpenseEntity entity = expenseRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Expense not found: " + entityId));
        entity.setApprovalStatus(status);
        if (note != null) entity.setApprovalNote(note);
        expenseRepo.save(entity);
        log.info("Expense approval updated: id={}, status={}", entityId, status);
        return entity;
    }

    public List<ExpenseItem.AbsencePeriod> findKmExpenseAbsences(String company, String month) {
        try {
            if (isNullOrBlank(month)) return List.of();
            UUID tenantId = TenantContext.getTenantId();
            YearMonth ym = YearMonth.parse(month);
            List<ExpenseItem.AbsencePeriod> result = new ArrayList<>();
            for (ExpenseEntity e : expenseRepo.findByTenantIdAndExpenseDateBetween(tenantId, ym.atDay(1), ym.atEndOfMonth())) {
                String type = e.getType() != null ? e.getType().toLowerCase(Locale.ROOT) : "";
                if (!type.contains("km") && !type.contains("kilom")) continue;
                if (!isNullOrBlank(company) && !company.trim().equalsIgnoreCase(
                        e.getCompany() != null ? e.getCompany().trim() : "")) continue;
                if (e.getAbsencePeriodsJson() != null && !e.getAbsencePeriodsJson().isBlank()) {
                    try {
                        List<ExpenseItem.AbsencePeriod> periods = objectMapper.readValue(e.getAbsencePeriodsJson(),
                                objectMapper.getTypeFactory().constructCollectionType(List.class, ExpenseItem.AbsencePeriod.class));
                        result.addAll(periods);
                    } catch (Exception ex) { log.warn("Could not parse absencePeriods: {}", ex.getMessage()); }
                }
            }
            return result;
        } catch (Exception e) { return List.of(); }
    }

    // ── Profil consultant (barème km) ─────────────────────────────────────────

    public ConsultantProfile findConsultantProfile(String email, UUID tenantId) {
        if (isNullOrBlank(email) || tenantId == null) return null;
        return consultantProfileRepo.findByTenantIdAndEmailIgnoreCase(tenantId, email)
                .map(this::toConsultantProfile).orElse(null);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void persistChunk(String id, String text, String source, float[] embedding) {
        try {
            UUID tenantId = TenantContext.getTenantId();
            DocumentChunkEntity chunk;
            if (id != null && !id.isBlank()) {
                chunk = documentChunkRepo.findById(UUID.fromString(id)).orElse(new DocumentChunkEntity());
                chunk.setId(UUID.fromString(id));
            } else {
                chunk = new DocumentChunkEntity();
            }
            chunk.setTenantId(tenantId);
            chunk.setContent(text);
            chunk.setSource(source);
            chunk.setEmbedding(toPgVectorString(embedding));
            documentChunkRepo.save(chunk);
        } catch (Exception e) { log.error("persistChunk error: {}", e.getMessage(), e); }
    }

    private ExpenseItem toExpenseItem(ExpenseEntity e) {
        ExpenseItem item = new ExpenseItem();
        item.setId(e.getExpenseId());
        item.setAmount(e.getAmount() != null ? e.getAmount().doubleValue() : null);
        item.setCurrency(e.getCurrency()); item.setType(e.getType());
        item.setKm(e.getKm() != null ? e.getKm().doubleValue() : null);
        item.setDate(e.getExpenseDate() != null ? e.getExpenseDate().toString() : e.getDateText());
        item.setDescription(e.getDescription()); item.setOriginalText(e.getOriginalText());
        item.setPaymentMode(e.getPaymentMode()); item.setAddress(e.getAddress());
        item.setCompany(e.getCompany()); item.setConsultantEmail(e.getConsultantEmail());
        item.setApprovalStatus(e.getApprovalStatus()); item.setApprovalNote(e.getApprovalNote());
        item.setWeaviateId(e.getId().toString()); item.setStatus(e.getApprovalStatus());
        if (e.getAbsencePeriodsJson() != null && !e.getAbsencePeriodsJson().isBlank()) {
            try {
                item.setAbsencePeriods(objectMapper.readValue(e.getAbsencePeriodsJson(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, ExpenseItem.AbsencePeriod.class)));
            } catch (Exception ignored) {}
        }
        return item;
    }

    private ConsultantProfile toConsultantProfile(ConsultantProfileEntity e) {
        return new ConsultantProfile(
                e.getId() != null ? e.getId().toString() : null,
                e.getEmail(), e.getName(), e.getRole(), e.getCompany(),
                e.getClientName(), e.getClientAddress(), e.getClientRcs(), e.getClientContactEmail(),
                e.getTjm() != null ? e.getTjm().doubleValue() : null, e.getActive(),
                e.getVehicleType() != null ? e.getVehicleType() : VehicleType.CAR,
                e.getFiscalPower() != null ? e.getFiscalPower() : 7,
                e.getKmAnnual() != null ? e.getKmAnnual() : kmAnnual,
                e.getIsConsultant() != null ? e.getIsConsultant() : true,
                e.getDailyCost() != null ? e.getDailyCost().doubleValue() : null);
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

    private String toPgVectorString(float[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) { if (i > 0) sb.append(","); sb.append(arr[i]); }
        return sb.append("]").toString();
    }

    private String toPgVectorString(List<Double> vector) {
        return "[" + vector.stream().map(String::valueOf).collect(Collectors.joining(",")) + "]";
    }

    private boolean isNullOrBlank(String s) { return s == null || s.isBlank(); }

    private LocalDate parseFlexibleDate(String date) {
        if (date == null || date.isBlank()) return null;
        try { return LocalDate.parse(date); } catch (Exception ignored) {}
        try { return LocalDateTime.parse(date, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toLocalDate(); } catch (Exception ignored) {}
        try { return OffsetDateTime.parse(date).toLocalDate(); } catch (Exception ignored) {}
        return null;
    }
}
