package io.multiagent.invoice.repository;

import io.multiagent.invoice.client.LLMAIClient;
import io.multiagent.invoice.model.InvoiceLookupRequest;
import io.multiagent.invoice.model.SimpleInvoiceRequest;
import io.weaviate.client.WeaviateClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static io.multiagent.invoice.util.WeaviateUtils.*;

@Slf4j
@Repository
public class InvoiceWeaviateRepository {

    private final WeaviateClient client;
    private final LLMAIClient llm;
    private final String invoiceClassName;

    public InvoiceWeaviateRepository(
            WeaviateClient client,
            LLMAIClient llm,
            @Value("${weaviate.invoice-class:Invoice}") String invoiceClassName) {
        this.client = client;
        this.llm = llm;
        this.invoiceClassName = invoiceClassName;
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
            List<Double> vector = llm.embed(llm.getEmbeddingModel(), text);
            Map<String, Object> props = new java.util.HashMap<>();
            props.put("invoiceName", invoice.invoiceName());
            props.put("invoiceDate", formatRfc3339(invoice.invoiceDate()));
            props.put("billingMonth", invoice.billingMonth());
            props.put("sellerCompanyName", invoice.sellerCompanyName());
            props.put("sellerAddress", invoice.sellerAddress());
            props.put("sellerRcs", invoice.sellerRcs());
            props.put("clientCompanyName", invoice.clientCompanyName());
            props.put("clientAddress", invoice.clientAddress());
            props.put("clientRcs", invoice.clientRcs());
            props.put("invoiceTitle", invoice.invoiceTitle());
            props.put("daysCount", invoice.daysCount() != null ? (int) Math.round(invoice.daysCount()) : null);
            props.put("daysExact", invoice.daysCount());
            props.put("unitPriceHt", invoice.unitPriceHt());
            props.put("totalHt", invoice.totalHt());
            props.put("vatRate", invoice.vatRate());
            props.put("totalTtc", invoice.totalTtc());
            props.put("currency", invoice.currency());
            props.put("paymentDueDate", invoice.paymentDueDate() == null ? null : formatRfc3339(invoice.paymentDueDate()));
            props.put("latePaymentClause", invoice.latePaymentClause());
            props.put("notes", invoice.notes());
            props.put("consultantEmail", invoice.consultantEmail() != null ? invoice.consultantEmail().toLowerCase().trim() : "");
            props.put("sourceText", sourceText);
            props.put("pdfPath", pdfPath);
            props.put("excelPath", excelPath);
            props.put("text", text);

            var creator = client.data().creator()
                    .withClassName(invoiceClassName)
                    .withProperties(props)
                    .withVector(toFloatArray(vector));

            if (id != null && !id.isBlank()) {
                creator = creator.withID(id);
            }

            var result = creator.run();
            if (result.hasErrors()) {
                log.error("Weaviate indexSimpleInvoice error: {}", result.getError());
            } else {
                log.info("Weaviate: facture indexée (invoiceName={})", invoice.invoiceName());
            }
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
            var response = client.data().objectsGetter()
                    .withClassName(invoiceClassName)
                    .withLimit(1000)
                    .run();

            if (response.hasErrors() || response.getResult() == null) {
                log.error("findInvoices fetch error: {}", response.getError());
                return List.of();
            }

            return response.getResult().stream()
                    .filter(object -> object != null && object.getProperties() != null)
                    .map(object -> toSimpleInvoiceRequest(object.getProperties()))
                    .filter(invoice -> invoice != null
                            && request.billingMonth().equalsIgnoreCase(safeString(invoice.billingMonth()))
                            && request.sellerCompanyName().trim().equalsIgnoreCase(safeString(invoice.sellerCompanyName()).trim())
                            && matchesInvoiceName(request.invoiceName(), invoice.invoiceName()))
                    .sorted(Comparator.comparing(
                            invoice -> safeString(invoice.invoiceName()),
                            String.CASE_INSENSITIVE_ORDER
                    ))
                    .toList();
        } catch (Exception e) {
            log.error("findInvoices exception: {}", e.getMessage(), e);
            return List.of();
        }
    }

    public List<SimpleInvoiceRequest> findInvoicesByPeriod(String start, String end, String company, String consultantEmail) {
        try {
            var response = client.data().objectsGetter()
                    .withClassName(invoiceClassName)
                    .withLimit(1000)
                    .run();

            if (response.hasErrors() || response.getResult() == null) {
                log.error("findInvoicesByPeriod fetch error: {}", response.getError());
                return List.of();
            }

            return response.getResult().stream()
                    .filter(obj -> obj != null && obj.getProperties() != null)
                    .map(obj -> toSimpleInvoiceRequest(obj.getProperties()))
                    .filter(inv -> inv != null)
                    .filter(inv -> {
                        String bm = safeString(inv.billingMonth());
                        if (!isNullOrBlank(start) && bm.compareTo(start) < 0) return false;
                        if (!isNullOrBlank(end)   && bm.compareTo(end)   > 0) return false;
                        return true;
                    })
                    .filter(inv -> isNullOrBlank(company)
                            || safeString(inv.sellerCompanyName()).trim().equalsIgnoreCase(company.trim()))
                    .filter(inv -> isNullOrBlank(consultantEmail)
                            || isNullOrBlank(safeString(inv.consultantEmail()))
                            || safeString(inv.consultantEmail()).trim().equalsIgnoreCase(consultantEmail.trim()))
                    .sorted(Comparator.comparing(inv -> safeString(inv.billingMonth()), String.CASE_INSENSITIVE_ORDER))
                    .toList();
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
            var response = client.data().objectsGetter()
                    .withClassName(invoiceClassName)
                    .withLimit(1000)
                    .run();

            if (response.hasErrors() || response.getResult() == null) {
                log.error("deleteInvoices fetch error: {}", response.getError());
                return -1;
            }

            int deleted = 0;
            for (var object : response.getResult()) {
                if (object == null || object.getProperties() == null) {
                    continue;
                }
                Map<String, Object> props = object.getProperties();
                if (!matchesInvoiceDeletion(props, request)) {
                    continue;
                }

                var deleteResult = client.data().deleter()
                        .withClassName(invoiceClassName)
                        .withID(object.getId())
                        .run();
                if (deleteResult.hasErrors() || !Boolean.TRUE.equals(deleteResult.getResult())) {
                    log.error("Weaviate invoice delete error for id={}: {}", object.getId(), deleteResult.getError());
                } else {
                    deleted++;
                }
            }
            log.info("Weaviate invoice delete -> invoiceName={}, sellerCompanyName={}, billingMonth={}, deleted={}",
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

    private SimpleInvoiceRequest toSimpleInvoiceRequest(Map<String, Object> props) {
        if (props == null) {
            return null;
        }
        return new SimpleInvoiceRequest(
                safeString(props.get("invoiceName")),
                parseLocalDateValue(props.get("invoiceDate")),
                safeString(props.get("billingMonth")),
                safeString(props.get("sellerCompanyName")),
                safeString(props.get("sellerAddress")),
                safeString(props.get("sellerRcs")),
                safeString(props.get("clientCompanyName")),
                safeString(props.get("clientAddress")),
                safeString(props.get("clientRcs")),
                safeString(props.get("invoiceTitle")),
                props.get("daysExact") != null ? parseDouble(props.get("daysExact")) : parseDouble(props.get("daysCount")),
                parseBigDecimal(props.get("unitPriceHt")),
                parseBigDecimal(props.get("totalHt")),
                parseBigDecimal(props.get("vatRate")),
                parseBigDecimal(props.get("totalTtc")),
                safeString(props.get("currency")),
                parseLocalDateValue(props.get("paymentDueDate")),
                safeString(props.get("latePaymentClause")),
                safeString(props.get("notes")),
                null,   // absencePeriods
                safeString(props.get("consultantEmail"))
        );
    }

    private boolean matchesInvoiceName(String requestedInvoiceName, String storedInvoiceName) {
        if (requestedInvoiceName == null || requestedInvoiceName.isBlank()) {
            return true;
        }
        if (storedInvoiceName == null || storedInvoiceName.isBlank()) {
            return false;
        }
        return requestedInvoiceName.trim().equalsIgnoreCase(storedInvoiceName.trim());
    }

    private boolean matchesInvoiceDeletion(Map<String, Object> props, InvoiceLookupRequest request) {
        String storedInvoiceName = safeString(props.get("invoiceName"));
        String storedSeller = safeString(props.get("sellerCompanyName"));
        String storedBillingMonth = safeString(props.get("billingMonth"));

        if (!request.invoiceName().trim().equalsIgnoreCase(storedInvoiceName.trim())) {
            return false;
        }
        if (!request.sellerCompanyName().trim().equalsIgnoreCase(storedSeller.trim())) {
            return false;
        }
        if (request.billingMonth() == null || request.billingMonth().isBlank()) {
            return true;
        }
        return request.billingMonth().trim().equalsIgnoreCase(storedBillingMonth.trim());
    }
}
