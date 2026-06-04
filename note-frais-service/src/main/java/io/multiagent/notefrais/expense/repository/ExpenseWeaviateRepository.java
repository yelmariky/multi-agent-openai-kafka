package io.multiagent.notefrais.expense.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.multiagent.notefrais.model.ExpenseItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Repository
@RequiredArgsConstructor
public class ExpenseWeaviateRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Value("${ai-core.company-name:IA-INSIGHT}")
    private String defaultCompany;

    // -----------------------------------------------------------------------
    // Write
    // -----------------------------------------------------------------------

    public void indexExpense(String id, ExpenseItem item, String source, boolean duplicate, String hash) {
        if (item == null || item.getAmount() == null
                || isNullOrBlank(item.getCurrency())
                || isNullOrBlank(item.getType())
                || isNullOrBlank(item.getPaymentMode())
                || isNullOrBlank(item.getAddress())) {
            log.warn("indexExpense ignoré: champs obligatoires manquants pour {}", item);
            return;
        }
        try {
            String absJson = null;
            if (item.getAbsencePeriods() != null && !item.getAbsencePeriods().isEmpty()) {
                absJson = objectMapper.writeValueAsString(item.getAbsencePeriods());
            }
            String text = buildExpenseText(item, source, duplicate);
            String company = item.getCompany() != null ? item.getCompany() : defaultCompany;
            String email = item.getConsultantEmail() != null ? item.getConsultantEmail().toLowerCase() : null;
            String status = item.getApprovalStatus() != null ? item.getApprovalStatus() : "PENDING";

            LocalDate date = null;
            if (item.getDate() != null && !item.getDate().isBlank()) {
                try { date = LocalDate.parse(item.getDate()); } catch (Exception ignored) {}
            }

            if (id != null && !id.isBlank()) {
                // Upsert by UUID
                jdbc.update("""
                    INSERT INTO expenses (id, company_id, consultant_email, expense_id, type, description,
                        amount, currency, expense_date, payment_mode, approval_status, approval_note,
                        km, source, address, original_text, hash, duplicate_flag, text, absence_periods_json)
                    VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET
                        type = EXCLUDED.type, amount = EXCLUDED.amount, currency = EXCLUDED.currency,
                        expense_date = EXCLUDED.expense_date, description = EXCLUDED.description,
                        approval_status = EXCLUDED.approval_status, updated_at = now()
                    """,
                    id, company, email, item.getId(),
                    item.getType(), item.getDescription(),
                    item.getAmount() != null ? BigDecimal.valueOf(item.getAmount()) : null,
                    item.getCurrency(), date, item.getPaymentMode(),
                    status, item.getApprovalNote(),
                    item.getKm() != null ? BigDecimal.valueOf(item.getKm()) : null,
                    source, item.getAddress(), item.getOriginalText(), hash, duplicate, text, absJson);
            } else {
                jdbc.update("""
                    INSERT INTO expenses (company_id, consultant_email, expense_id, type, description,
                        amount, currency, expense_date, payment_mode, approval_status, approval_note,
                        km, source, address, original_text, hash, duplicate_flag, text, absence_periods_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    company, email, item.getId(),
                    item.getType(), item.getDescription(),
                    item.getAmount() != null ? BigDecimal.valueOf(item.getAmount()) : null,
                    item.getCurrency(), date, item.getPaymentMode(),
                    status, item.getApprovalNote(),
                    item.getKm() != null ? BigDecimal.valueOf(item.getKm()) : null,
                    source, item.getAddress(), item.getOriginalText(), hash, duplicate, text, absJson);
            }
            log.info("Expense saved to PostgreSQL (expenseId={})", item.getId());
        } catch (Exception e) {
            log.error("indexExpense exception: {}", e.getMessage(), e);
        }
    }

    public void updateExpenseEmbedding(String id, List<Double> vector) {
        if (id == null || id.isBlank() || vector == null || vector.isEmpty()) return;
        try {
            jdbc.update("UPDATE expenses SET embedding = ?::vector WHERE id = ?::uuid",
                    toVec(vector), id);
        } catch (Exception e) {
            log.error("updateExpenseEmbedding exception: {}", e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Read
    // -----------------------------------------------------------------------

    public List<ExpenseItem> findExpensesBetween(LocalDate start, LocalDate end) {
        return findExpensesBetween(start, end, null, null, null);
    }

    public List<ExpenseItem> findExpensesBetween(LocalDate start, LocalDate end, String type, String currency) {
        return findExpensesBetween(start, end, type, currency, null);
    }

    public List<ExpenseItem> findExpensesBetween(LocalDate start, LocalDate end, String type, String currency, String consultantEmail) {
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT * FROM expenses WHERE expense_date >= ? AND expense_date <= ?");
            List<Object> params = new ArrayList<>();
            params.add(start);
            params.add(end);
            if (!isNullOrBlank(type)) {
                sql.append(" AND type = ?"); params.add(type);
            }
            if (!isNullOrBlank(currency)) {
                sql.append(" AND currency = ?"); params.add(currency.toUpperCase());
            }
            if (!isNullOrBlank(consultantEmail)) {
                sql.append(" AND consultant_email = ?"); params.add(consultantEmail.toLowerCase());
            }
            sql.append(" ORDER BY expense_date, expense_id LIMIT 500");
            return jdbc.query(sql.toString(), expenseMapper(), params.toArray());
        } catch (Exception e) {
            log.error("findExpensesBetween exception: {}", e.getMessage(), e);
            return List.of();
        }
    }

    public int findMaxExpenseId(YearMonth ym) {
        try {
            LocalDate start = ym.atDay(1);
            LocalDate end = ym.plusMonths(1).atDay(1).minusDays(1);
            Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(expense_id), 0) FROM expenses WHERE expense_date >= ? AND expense_date <= ?",
                Integer.class, start, end);
            return max != null ? max : 0;
        } catch (Exception e) {
            log.warn("findMaxExpenseId exception: {}", e.getMessage());
            return 0;
        }
    }

    // -----------------------------------------------------------------------
    // Delete
    // -----------------------------------------------------------------------

    public int deleteExpensesByDate(LocalDate date) {
        return deleteExpensesByDate(date, null);
    }

    public int deleteExpensesByDate(LocalDate date, String company) {
        if (date == null) return 0;
        try {
            if (isNullOrBlank(company)) {
                return jdbc.update("DELETE FROM expenses WHERE expense_date = ?", date);
            }
            int deleted = jdbc.update(
                "DELETE FROM expenses WHERE expense_date = ? AND company_id = ?", date, company);
            if (deleted == 0) {
                deleted = jdbc.update("DELETE FROM expenses WHERE expense_date = ?", date);
            }
            return deleted;
        } catch (Exception e) {
            log.error("deleteExpensesByDate exception: {}", e.getMessage(), e);
            return -1;
        }
    }

    public int deleteExpenseByIdAndMonth(int expenseId, YearMonth ym) {
        return deleteExpenseByIdAndMonth(expenseId, ym, null);
    }

    public int deleteExpenseByIdAndMonth(int expenseId, YearMonth ym, String company) {
        try {
            LocalDate start = ym.atDay(1);
            LocalDate end = ym.plusMonths(1).atDay(1).minusDays(1);
            if (isNullOrBlank(company)) {
                return jdbc.update(
                    "DELETE FROM expenses WHERE expense_id = ? AND expense_date >= ? AND expense_date <= ?",
                    expenseId, start, end);
            }
            int deleted = jdbc.update(
                "DELETE FROM expenses WHERE expense_id = ? AND expense_date >= ? AND expense_date <= ? AND company_id = ?",
                expenseId, start, end, company);
            if (deleted == 0) {
                deleted = jdbc.update(
                    "DELETE FROM expenses WHERE expense_id = ? AND expense_date >= ? AND expense_date <= ?",
                    expenseId, start, end);
            }
            return deleted;
        } catch (Exception e) {
            log.error("deleteExpenseByIdAndMonth exception: {}", e.getMessage(), e);
            return -1;
        }
    }

    public int deleteExpenseById(int expenseId) {
        return deleteExpenseById(expenseId, null);
    }

    public int deleteExpenseById(int expenseId, String company) {
        try {
            if (isNullOrBlank(company)) {
                return jdbc.update("DELETE FROM expenses WHERE expense_id = ?", expenseId);
            }
            int deleted = jdbc.update(
                "DELETE FROM expenses WHERE expense_id = ? AND company_id = ?", expenseId, company);
            if (deleted == 0) {
                deleted = jdbc.update("DELETE FROM expenses WHERE expense_id = ?", expenseId);
            }
            return deleted;
        } catch (Exception e) {
            log.error("deleteExpenseById exception: {}", e.getMessage(), e);
            return -1;
        }
    }

    // -----------------------------------------------------------------------
    // Update
    // -----------------------------------------------------------------------

    public void updateExpenseApproval(String weaviateId, String status, String note) {
        try {
            int updated = jdbc.update(
                "UPDATE expenses SET approval_status = ?, approval_note = ?, updated_at = now() WHERE id = ?::uuid",
                status, note, weaviateId);
            if (updated == 0) {
                throw new RuntimeException("updateExpenseApproval: no row found for id=" + weaviateId);
            }
            log.info("Expense approval updated: id={}, status={}", weaviateId, status);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("updateExpenseApproval exception: {}", e.getMessage(), e);
            throw new RuntimeException("updateExpenseApproval exception: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Absences (for km RAG)
    // -----------------------------------------------------------------------

    public List<ExpenseItem.AbsencePeriod> findKmExpenseAbsences(String company, String month) {
        try {
            if (isNullOrBlank(month)) return List.of();
            List<String> rows = jdbc.queryForList(
                "SELECT absence_periods_json FROM expenses WHERE (type ILIKE '%km%' OR type ILIKE '%kilom%') AND TO_CHAR(expense_date, 'YYYY-MM') = ? AND (? IS NULL OR company_id ILIKE ?) AND absence_periods_json IS NOT NULL",
                String.class, month, company, company);
            List<ExpenseItem.AbsencePeriod> result = new ArrayList<>();
            for (String json : rows) {
                if (json == null || json.isBlank()) continue;
                try {
                    List<ExpenseItem.AbsencePeriod> periods = objectMapper.readValue(json,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, ExpenseItem.AbsencePeriod.class));
                    result.addAll(periods);
                } catch (Exception e) {
                    log.warn("findKmExpenseAbsences: could not parse absencePeriodsJson: {}", e.getMessage());
                }
            }
            return result;
        } catch (Exception e) {
            log.error("findKmExpenseAbsences exception: {}", e.getMessage(), e);
            return List.of();
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private RowMapper<ExpenseItem> expenseMapper() {
        return (rs, rowNum) -> mapExpense(rs);
    }

    private ExpenseItem mapExpense(ResultSet rs) throws SQLException {
        ExpenseItem item = new ExpenseItem();
        item.setWeaviateId(rs.getString("id"));
        Object expenseId = rs.getObject("expense_id");
        if (expenseId instanceof Number n) item.setId(n.intValue());
        item.setType(rs.getString("type"));
        item.setDescription(rs.getString("description"));
        Object amount = rs.getObject("amount");
        if (amount instanceof Number n) item.setAmount(n.doubleValue());
        item.setCurrency(rs.getString("currency"));
        java.sql.Date d = rs.getDate("expense_date");
        if (d != null) item.setDate(d.toLocalDate().toString());
        item.setPaymentMode(rs.getString("payment_mode"));
        item.setApprovalStatus(rs.getString("approval_status"));
        item.setApprovalNote(rs.getString("approval_note"));
        Object km = rs.getObject("km");
        if (km instanceof Number n) item.setKm(n.doubleValue());
        item.setAddress(rs.getString("address"));
        item.setOriginalText(rs.getString("original_text"));
        item.setConsultantEmail(rs.getString("consultant_email"));
        item.setCompany(rs.getString("company_id"));
        String absJson = rs.getString("absence_periods_json");
        if (absJson != null && !absJson.isBlank()) {
            try {
                item.setAbsencePeriods(new ObjectMapper().readValue(absJson,
                    new ObjectMapper().getTypeFactory().constructCollectionType(List.class, ExpenseItem.AbsencePeriod.class)));
            } catch (Exception ignored) {}
        }
        return item;
    }

    private static String buildExpenseText(ExpenseItem item, String source, boolean duplicate) {
        return "date=%s | amount=%s %s | type=%s | paymentMode=%s | address=%s | desc=%s | source=%s | duplicate=%s | original=%s"
            .formatted(item.getDate(), item.getAmount(), item.getCurrency(), item.getType(),
                item.getPaymentMode(), item.getAddress(), item.getDescription(), source, duplicate, item.getOriginalText());
    }

    private static boolean isNullOrBlank(String s) {
        return s == null || s.isBlank();
    }

    static String toVec(List<Double> v) {
        return "[" + v.stream().map(Object::toString).collect(Collectors.joining(",")) + "]";
    }
}
