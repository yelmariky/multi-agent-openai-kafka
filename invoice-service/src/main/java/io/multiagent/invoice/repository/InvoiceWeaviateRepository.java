package io.multiagent.invoice.repository;

import io.multiagent.invoice.client.LLMAIClient;
import io.multiagent.invoice.model.InvoiceLookupRequest;
import io.multiagent.invoice.model.SimpleInvoiceRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Repository
public class InvoiceWeaviateRepository {

    private final JdbcTemplate jdbc;
    private final LLMAIClient llm;

    public InvoiceWeaviateRepository(JdbcTemplate jdbc, LLMAIClient llm) {
        this.jdbc = jdbc;
        this.llm = llm;
    }

    public void indexSimpleInvoice(String id, SimpleInvoiceRequest invoice, String sourceText, String pdfPath, String excelPath) {
        try {
            if (invoice == null
                    || isNullOrBlank(invoice.sellerCompanyName())
                    || isNullOrBlank(invoice.clientCompanyName())
                    || invoice.invoiceDate() == null) {
                log.warn("indexSimpleInvoice ignoré: champs obligatoires manquants (sellerCompanyName/clientCompanyName/invoiceDate) pour {}", invoice);
                return;
            }

            String text = buildInvoiceText(invoice, sourceText, pdfPath, excelPath);

            // Resolve the row id: either use the provided one or let the DB generate one
            String resolvedId;
            if (id != null && !id.isBlank()) {
                resolvedId = id;
                jdbc.update("""
                        INSERT INTO invoices (
                            id, invoice_name, invoice_date, billing_month,
                            seller_company_name, seller_address, seller_rcs,
                            client_company_name, client_address, client_rcs,
                            invoice_title, days_count, unit_price_ht, total_ht,
                            vat_rate, total_ttc, currency, payment_due_date,
                            late_payment_clause, notes, consultant_email,
                            source_text, pdf_path, excel_path, text
                        ) VALUES (
                            ?::uuid, ?, ?, ?,
                            ?, ?, ?,
                            ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?, ?,
                            ?, ?, ?, ?
                        )
                        ON CONFLICT (id) DO UPDATE SET
                            invoice_name        = EXCLUDED.invoice_name,
                            invoice_date        = EXCLUDED.invoice_date,
                            billing_month       = EXCLUDED.billing_month,
                            seller_company_name = EXCLUDED.seller_company_name,
                            seller_address      = EXCLUDED.seller_address,
                            seller_rcs          = EXCLUDED.seller_rcs,
                            client_company_name = EXCLUDED.client_company_name,
                            client_address      = EXCLUDED.client_address,
                            client_rcs          = EXCLUDED.client_rcs,
                            invoice_title       = EXCLUDED.invoice_title,
                            days_count          = EXCLUDED.days_count,
                            unit_price_ht       = EXCLUDED.unit_price_ht,
                            total_ht            = EXCLUDED.total_ht,
                            vat_rate            = EXCLUDED.vat_rate,
                            total_ttc           = EXCLUDED.total_ttc,
                            currency            = EXCLUDED.currency,
                            payment_due_date    = EXCLUDED.payment_due_date,
                            late_payment_clause = EXCLUDED.late_payment_clause,
                            notes               = EXCLUDED.notes,
                            consultant_email    = EXCLUDED.consultant_email,
                            source_text         = EXCLUDED.source_text,
                            pdf_path            = EXCLUDED.pdf_path,
                            excel_path          = EXCLUDED.excel_path,
                            text                = EXCLUDED.text,
                            updated_at          = now()
                        """,
                        resolvedId,
                        invoice.invoiceName(),
                        toSqlDate(invoice.invoiceDate()),
                        invoice.billingMonth(),
                        invoice.sellerCompanyName(),
                        invoice.sellerAddress(),
                        invoice.sellerRcs(),
                        invoice.clientCompanyName(),
                        invoice.clientAddress(),
                        invoice.clientRcs(),
                        invoice.invoiceTitle(),
                        invoice.daysCount(),
                        invoice.unitPriceHt(),
                        invoice.totalHt(),
                        invoice.vatRate(),
                        invoice.totalTtc(),
                        invoice.currency(),
                        toSqlDate(invoice.paymentDueDate()),
                        invoice.latePaymentClause(),
                        invoice.notes(),
                        invoice.consultantEmail() != null ? invoice.consultantEmail().toLowerCase().trim() : "",
                        sourceText,
                        pdfPath,
                        excelPath,
                        text
                );
            } else {
                // Let the DB generate a UUID; retrieve it to store embedding
                resolvedId = jdbc.queryForObject("""
                        INSERT INTO invoices (
                            invoice_name, invoice_date, billing_month,
                            seller_company_name, seller_address, seller_rcs,
                            client_company_name, client_address, client_rcs,
                            invoice_title, days_count, unit_price_ht, total_ht,
                            vat_rate, total_ttc, currency, payment_due_date,
                            late_payment_clause, notes, consultant_email,
                            source_text, pdf_path, excel_path, text
                        ) VALUES (
                            ?, ?, ?,
                            ?, ?, ?,
                            ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?, ?, ?,
                            ?, ?, ?,
                            ?, ?, ?, ?
                        )
                        RETURNING id::text
                        """,
                        String.class,
                        invoice.invoiceName(),
                        toSqlDate(invoice.invoiceDate()),
                        invoice.billingMonth(),
                        invoice.sellerCompanyName(),
                        invoice.sellerAddress(),
                        invoice.sellerRcs(),
                        invoice.clientCompanyName(),
                        invoice.clientAddress(),
                        invoice.clientRcs(),
                        invoice.invoiceTitle(),
                        invoice.daysCount(),
                        invoice.unitPriceHt(),
                        invoice.totalHt(),
                        invoice.vatRate(),
                        invoice.totalTtc(),
                        invoice.currency(),
                        toSqlDate(invoice.paymentDueDate()),
                        invoice.latePaymentClause(),
                        invoice.notes(),
                        invoice.consultantEmail() != null ? invoice.consultantEmail().toLowerCase().trim() : "",
                        sourceText,
                        pdfPath,
                        excelPath,
                        text
                );
            }

            // Update embedding asynchronously-safe: best-effort, same thread
            try {
                List<Double> vector = llm.embed(llm.getEmbeddingModel(), text);
                if (vector != null && !vector.isEmpty()) {
                    jdbc.update("UPDATE invoices SET embedding = ?::vector WHERE id = ?::uuid",
                            toVec(vector), resolvedId);
                }
            } catch (Exception embEx) {
                log.warn("indexSimpleInvoice: embedding update skipped for id={}: {}", resolvedId, embEx.getMessage());
            }

            log.info("PostgreSQL: facture indexée (invoiceName={})", invoice.invoiceName());
        } catch (Exception e) {
            log.error("Exception indexSimpleInvoice: {}", e.getMessage(), e);
        }
    }

    public List<SimpleInvoiceRequest> findInvoices(InvoiceLookupRequest request) {
        if (request == null
                || isNullOrBlank(request.billingMonth())
                || isNullOrBlank(request.sellerCompanyName())) {
            return List.of();
        }
        try {
            List<Object> params = new ArrayList<>();
            StringBuilder sql = new StringBuilder("""
                    SELECT * FROM invoices
                    WHERE billing_month ILIKE ?
                      AND seller_company_name ILIKE ?
                    """);
            params.add(request.billingMonth().trim());
            params.add(request.sellerCompanyName().trim());

            if (!isNullOrBlank(request.invoiceName())) {
                sql.append(" AND invoice_name ILIKE ?");
                params.add(request.invoiceName().trim());
            }

            sql.append(" ORDER BY invoice_name");

            return jdbc.query(sql.toString(), (rs, rowNum) -> toSimpleInvoiceRequest(rs), params.toArray());
        } catch (Exception e) {
            log.error("findInvoices exception: {}", e.getMessage(), e);
            return List.of();
        }
    }

    public List<SimpleInvoiceRequest> findInvoicesByPeriod(String start, String end, String company, String consultantEmail) {
        try {
            List<Object> params = new ArrayList<>();
            StringBuilder sql = new StringBuilder("SELECT * FROM invoices WHERE 1=1");

            if (!isNullOrBlank(start)) {
                sql.append(" AND billing_month >= ?");
                params.add(start.trim());
            }
            if (!isNullOrBlank(end)) {
                sql.append(" AND billing_month <= ?");
                params.add(end.trim());
            }
            if (!isNullOrBlank(company)) {
                sql.append(" AND seller_company_name ILIKE ?");
                params.add(company.trim());
            }
            if (!isNullOrBlank(consultantEmail)) {
                sql.append(" AND (consultant_email IS NULL OR consultant_email = '' OR consultant_email ILIKE ?)");
                params.add(consultantEmail.trim());
            }

            sql.append(" ORDER BY billing_month");

            return jdbc.query(sql.toString(), (rs, rowNum) -> toSimpleInvoiceRequest(rs), params.toArray());
        } catch (Exception e) {
            log.error("findInvoicesByPeriod exception: {}", e.getMessage(), e);
            return List.of();
        }
    }

    public int deleteInvoices(InvoiceLookupRequest request) {
        if (request == null
                || isNullOrBlank(request.invoiceName())
                || isNullOrBlank(request.sellerCompanyName())) {
            return 0;
        }
        try {
            List<Object> params = new ArrayList<>();
            StringBuilder sql = new StringBuilder("""
                    DELETE FROM invoices
                    WHERE invoice_name ILIKE ?
                      AND seller_company_name ILIKE ?
                    """);
            params.add(request.invoiceName().trim());
            params.add(request.sellerCompanyName().trim());

            if (!isNullOrBlank(request.billingMonth())) {
                sql.append(" AND billing_month ILIKE ?");
                params.add(request.billingMonth().trim());
            }

            int deleted = jdbc.update(sql.toString(), params.toArray());
            log.info("PostgreSQL invoice delete -> invoiceName={}, sellerCompanyName={}, billingMonth={}, deleted={}",
                    request.invoiceName(), request.sellerCompanyName(), request.billingMonth(), deleted);
            return deleted;
        } catch (Exception e) {
            log.error("deleteInvoices exception: {}", e.getMessage(), e);
            return -1;
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private String buildInvoiceText(SimpleInvoiceRequest invoice, String sourceText, String pdfPath, String excelPath) {
        return "invoiceName=%s | invoiceDate=%s | billingMonth=%s | seller=%s | sellerRcs=%s | client=%s | clientRcs=%s | title=%s | days=%s | unitPriceHt=%s | totalHt=%s | totalTtc=%s | currency=%s | dueDate=%s | pdfPath=%s | excelPath=%s | source=%s"
                .formatted(
                        invoice.invoiceName(),
                        invoice.invoiceDate(),
                        invoice.billingMonth(),
                        invoice.sellerCompanyName(),
                        invoice.sellerRcs(),
                        invoice.clientCompanyName(),
                        invoice.clientRcs(),
                        invoice.invoiceTitle(),
                        invoice.daysCount(),
                        invoice.unitPriceHt(),
                        invoice.totalHt(),
                        invoice.totalTtc(),
                        invoice.currency(),
                        invoice.paymentDueDate(),
                        pdfPath,
                        excelPath,
                        sourceText
                );
    }

    private SimpleInvoiceRequest toSimpleInvoiceRequest(ResultSet rs) throws SQLException {
        return new SimpleInvoiceRequest(
                rs.getString("invoice_name"),
                toLocalDate(rs.getDate("invoice_date")),
                rs.getString("billing_month"),
                rs.getString("seller_company_name"),
                rs.getString("seller_address"),
                rs.getString("seller_rcs"),
                rs.getString("client_company_name"),
                rs.getString("client_address"),
                rs.getString("client_rcs"),
                rs.getString("invoice_title"),
                toDouble(rs, "days_count"),
                toBigDecimal(rs, "unit_price_ht"),
                toBigDecimal(rs, "total_ht"),
                toBigDecimal(rs, "vat_rate"),
                toBigDecimal(rs, "total_ttc"),
                rs.getString("currency"),
                toLocalDate(rs.getDate("payment_due_date")),
                rs.getString("late_payment_clause"),
                rs.getString("notes"),
                null,   // absencePeriods — not persisted
                rs.getString("consultant_email")
        );
    }

    private static String toVec(List<Double> v) {
        return "[" + v.stream().map(Object::toString).collect(Collectors.joining(",")) + "]";
    }

    private static Date toSqlDate(LocalDate date) {
        return date == null ? null : Date.valueOf(date);
    }

    private static LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }

    private static Double toDouble(ResultSet rs, String col) throws SQLException {
        double val = rs.getDouble(col);
        return rs.wasNull() ? null : val;
    }

    private static BigDecimal toBigDecimal(ResultSet rs, String col) throws SQLException {
        BigDecimal val = rs.getBigDecimal(col);
        return rs.wasNull() ? null : val;
    }

    private static boolean isNullOrBlank(String s) {
        return s == null || s.isBlank();
    }
}
