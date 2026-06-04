package io.multiagent.cra.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.multiagent.cra.model.AbsencePeriod;
import io.multiagent.cra.model.CraDayEntry;
import io.multiagent.cra.model.CraRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Repository
@RequiredArgsConstructor
public class CraWeaviateRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    // -----------------------------------------------------------------------
    // Write
    // -----------------------------------------------------------------------

    public String indexCra(CraRequest cra) {
        try {
            String entriesJson = "[]";
            if (cra.entries() != null && !cra.entries().isEmpty()) {
                try { entriesJson = objectMapper.writeValueAsString(cra.entries()); }
                catch (Exception e) { log.warn("indexCra: could not serialize entries: {}", e.getMessage()); }
            }

            String company = safe(cra.company());
            String consultant = safe(cra.consultant());
            String billingMonth = safe(cra.billingMonth());

            if (cra.id() != null && !cra.id().isBlank()) {
                // Update by UUID
                jdbc.update("""
                    INSERT INTO cra (id, company_id, consultant, client_company, billing_month,
                        entries_json, total_days, status, submitted_at, validated_at, validated_by, refused_reason)
                    VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET
                        entries_json = EXCLUDED.entries_json, total_days = EXCLUDED.total_days,
                        status = EXCLUDED.status, submitted_at = EXCLUDED.submitted_at,
                        validated_at = EXCLUDED.validated_at, validated_by = EXCLUDED.validated_by,
                        refused_reason = EXCLUDED.refused_reason, updated_at = now()
                    """,
                    cra.id(), company, consultant, safe(cra.clientCompany()), billingMonth,
                    entriesJson, cra.totalDays(), safe(cra.status()),
                    cra.submittedAt() != null ? cra.submittedAt() : "",
                    cra.validatedAt() != null ? cra.validatedAt() : "",
                    cra.validatedBy() != null ? cra.validatedBy() : "",
                    cra.refusedReason() != null ? cra.refusedReason() : "");
                log.info("CRA upserted by id (id={}, consultant={}, month={})", cra.id(), consultant, billingMonth);
                return cra.id();
            } else {
                // Upsert by business key (company, consultant, billing_month)
                List<String> existing = jdbc.queryForList(
                    "SELECT id::text FROM cra WHERE company_id = ? AND consultant = ? AND billing_month = ?",
                    String.class, company, consultant, billingMonth);
                String uuid = existing.isEmpty() ? java.util.UUID.randomUUID().toString() : existing.get(0);
                jdbc.update("""
                    INSERT INTO cra (id, company_id, consultant, client_company, billing_month,
                        entries_json, total_days, status, submitted_at, validated_at, validated_by, refused_reason)
                    VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (company_id, consultant, billing_month) DO UPDATE SET
                        entries_json = EXCLUDED.entries_json, total_days = EXCLUDED.total_days,
                        status = EXCLUDED.status, submitted_at = EXCLUDED.submitted_at,
                        validated_at = EXCLUDED.validated_at, validated_by = EXCLUDED.validated_by,
                        refused_reason = EXCLUDED.refused_reason, updated_at = now()
                    """,
                    uuid, company, consultant, safe(cra.clientCompany()), billingMonth,
                    entriesJson, cra.totalDays(), safe(cra.status()),
                    cra.submittedAt() != null ? cra.submittedAt() : "",
                    cra.validatedAt() != null ? cra.validatedAt() : "",
                    cra.validatedBy() != null ? cra.validatedBy() : "",
                    cra.refusedReason() != null ? cra.refusedReason() : "");
                log.info("CRA upserted (uuid={}, consultant={}, month={})", uuid, consultant, billingMonth);
                return uuid;
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("indexCra exception: {}", e.getMessage(), e);
            throw new RuntimeException("indexCra exception: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Read
    // -----------------------------------------------------------------------

    public List<Map<String, Object>> findCrasByPeriod(String start, String end, String consultant, String company) {
        try {
            StringBuilder sql = new StringBuilder("SELECT * FROM cra WHERE 1=1");
            List<Object> params = new ArrayList<>();
            if (!isBlank(start)) { sql.append(" AND billing_month >= ?"); params.add(start); }
            if (!isBlank(end))   { sql.append(" AND billing_month <= ?"); params.add(end); }
            if (!isBlank(company)) { sql.append(" AND company_id ILIKE ?"); params.add(company.trim()); }
            if (!isBlank(consultant)) { sql.append(" AND LOWER(consultant) LIKE ?"); params.add("%" + consultant.toLowerCase(Locale.ROOT) + "%"); }
            sql.append(" ORDER BY billing_month");

            List<Map<String, Object>> result = new ArrayList<>();
            jdbc.query(sql.toString(), params.toArray(), rs -> {
                List<CraDayEntry> entries = List.of();
                String entriesJson = rs.getString("entries_json");
                if (entriesJson != null && !entriesJson.isBlank()) {
                    try {
                        entries = objectMapper.readValue(entriesJson,
                            objectMapper.getTypeFactory().constructCollectionType(List.class, CraDayEntry.class));
                    } catch (Exception e) { log.warn("findCrasByPeriod: could not parse entriesJson: {}", e.getMessage()); }
                }
                double totalDays = 0.0;
                Object td = rs.getObject("total_days");
                if (td instanceof Number n) totalDays = n.doubleValue();
                if (totalDays <= 0.0 && !entries.isEmpty()) {
                    totalDays = entries.stream().filter(e -> e != null && e.value() > 0).mapToDouble(CraDayEntry::value).sum();
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",            rs.getString("id"));
                row.put("consultant",    rs.getString("consultant"));
                row.put("company",       rs.getString("company_id"));
                row.put("clientCompany", rs.getString("client_company"));
                row.put("billingMonth",  rs.getString("billing_month"));
                row.put("entries",       entries);
                row.put("entriesJson",   entriesJson != null ? entriesJson : "[]");
                row.put("totalDays",     totalDays);
                row.put("status",        rs.getString("status"));
                row.put("submittedAt",   rs.getString("submitted_at"));
                row.put("validatedAt",   rs.getString("validated_at"));
                row.put("validatedBy",   rs.getString("validated_by"));
                row.put("refusedReason", rs.getString("refused_reason"));
                result.add(row);
            });
            return result;
        } catch (Exception e) {
            log.error("findCrasByPeriod exception: {}", e.getMessage(), e);
            return List.of();
        }
    }

    public List<AbsencePeriod> findCraAbsentDays(String consultant, String company, String month) {
        try {
            if (isBlank(consultant) || isBlank(month)) return List.of();
            List<Map<String, Object>> cras = findCrasByPeriod(month, month, consultant, company);
            List<AbsencePeriod> result = new ArrayList<>();
            for (Map<String, Object> cra : cras) {
                Object entriesObj = cra.get("entries");
                if (!(entriesObj instanceof List<?> entriesList)) continue;
                for (Object e : entriesList) {
                    if (!(e instanceof CraDayEntry entry)) continue;
                    if ("ABSENT".equalsIgnoreCase(entry.type())) {
                        String d = entry.date();
                        if (d != null && !d.isBlank()) result.add(new AbsencePeriod(d, d));
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.error("findCraAbsentDays exception: {}", e.getMessage(), e);
            return List.of();
        }
    }

    public List<AbsencePeriod> findKmExpenseAbsences(String company, String month) {
        try {
            if (isBlank(company) || isBlank(month)) return List.of();
            List<String> rows = jdbc.queryForList(
                "SELECT absence_periods_json FROM expenses WHERE (type ILIKE '%km%' OR type ILIKE '%kilom%') AND TO_CHAR(expense_date, 'YYYY-MM') = ? AND company_id ILIKE ? AND absence_periods_json IS NOT NULL",
                String.class, month, company.trim());
            List<AbsencePeriod> result = new ArrayList<>();
            for (String json : rows) {
                if (json == null || json.isBlank()) continue;
                try {
                    JsonNode arr = new ObjectMapper().readTree(json);
                    if (arr.isArray()) {
                        for (JsonNode n : arr) {
                            String from = n.path("from").asText("");
                            String to = n.path("to").asText("");
                            if (!from.isBlank() && !to.isBlank()) result.add(new AbsencePeriod(from, to));
                        }
                    }
                } catch (Exception e) { log.warn("findKmExpenseAbsences: parse error: {}", e.getMessage()); }
            }
            return result;
        } catch (Exception e) {
            log.error("findKmExpenseAbsences exception: {}", e.getMessage(), e);
            return List.of();
        }
    }

    // -----------------------------------------------------------------------
    // Delete
    // -----------------------------------------------------------------------

    public void deleteCra(String id) {
        try {
            if (isBlank(id)) { log.warn("deleteCra: id is blank, skipping"); return; }
            int deleted = jdbc.update("DELETE FROM cra WHERE id = ?::uuid", id);
            log.info("CRA deleted (id={}, rows={})", id, deleted);
        } catch (Exception e) {
            log.error("deleteCra exception: {}", e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String safe(String s) { return s != null ? s : ""; }
}
