package io.multiagent.invoice.service;

import io.multiagent.invoice.entity.InvoiceEntity;
import io.multiagent.invoice.entity.SellerProfileEntity;
import io.multiagent.invoice.infrastructure.tenant.TenantContext;
import io.multiagent.invoice.model.AbsencePeriod;
import io.multiagent.invoice.model.InvoiceLookupRequest;
import io.multiagent.invoice.model.SellerProfile;
import io.multiagent.invoice.model.SimpleInvoiceRequest;
import io.multiagent.invoice.repository.InvoiceJpaRepository;
import io.multiagent.invoice.repository.SellerProfileJpaRepository;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvoiceService {

    private static final BigDecimal DEFAULT_VAT_RATE = new BigDecimal("0.20");
    private static final String DEFAULT_CURRENCY = "EUR";
    private static final String DEFAULT_TITLE = "Consulting IT";
    private static final String DEFAULT_LATE_PAYMENT_CLAUSE =
            "En cas de retard de paiement, des penalites de retard sont exigibles des le jour suivant la date de reglement figurant sur la facture, sans qu'un rappel soit necessaire, a un taux au moins egal a trois fois le taux d'interet legal. Une indemnite forfaitaire pour frais de recouvrement de 40 euros est egalement due, conformement aux articles L441-9, L441-10 et D441-5 du Code de commerce.";
    private static final Locale FRENCH = Locale.FRANCE;

    private final Path storagePath;
    private final InvoiceJpaRepository invoiceRepo;
    private final SellerProfileJpaRepository sellerProfileRepo;
    private final JdbcTemplate jdbcTemplate;

    public InvoiceService(
            InvoiceJpaRepository invoiceRepo,
            SellerProfileJpaRepository sellerProfileRepo,
            JdbcTemplate jdbcTemplate,
            @Value("${app.invoices.storage-path:${AI_CORE_INVOICES_STORAGE_PATH:/data/invoices}}") String storagePathStr
    ) throws IOException {
        this.invoiceRepo = invoiceRepo;
        this.sellerProfileRepo = sellerProfileRepo;
        this.jdbcTemplate = jdbcTemplate;
        this.storagePath = Paths.get(storagePathStr);
        Files.createDirectories(this.storagePath);
    }

    public GeneratedInvoiceFiles generate(SimpleInvoiceRequest request) throws IOException {
        return generate(request, null);
    }

    @Transactional
    public GeneratedInvoiceFiles generate(SimpleInvoiceRequest request, String sourceText) throws IOException {
        SimpleInvoiceRequest normalized = normalize(request);
        String baseFilename = fileSafeName(normalized);

        byte[] pdfBytes = buildPdf(normalized);
        byte[] excelBytes = buildExcel(normalized);

        Path pdfPath = storagePath.resolve(baseFilename + ".pdf");
        Path excelPath = storagePath.resolve(baseFilename + ".xlsx");
        Files.write(pdfPath, pdfBytes);
        Files.write(excelPath, excelBytes);

        UUID tenantId = TenantContext.getTenantId();

        // Upsert: delete any existing invoice with the same name before re-indexing
        if (!isBlank(normalized.invoiceName()) && !isBlank(normalized.sellerCompanyName())) {
            List<InvoiceEntity> existing = invoiceRepo
                    .findByTenantIdAndInvoiceNameIgnoreCaseAndSellerCompanyNameIgnoreCase(
                            tenantId, normalized.invoiceName(), normalized.sellerCompanyName());
            invoiceRepo.deleteAll(existing);
        }

        InvoiceEntity entity = toEntity(normalized, tenantId, sourceText, pdfPath.toString(), excelPath.toString());
        invoiceRepo.save(entity);

        return new GeneratedInvoiceFiles(normalized, pdfPath, excelPath, pdfBytes, excelBytes);
    }

    public GeneratedInvoiceFiles generateFromDb(InvoiceLookupRequest request) throws IOException {
        UUID tenantId = TenantContext.getTenantId();
        List<InvoiceEntity> entities = findInvoiceEntities(tenantId, request);
        if (entities.isEmpty()) {
            throw new IllegalStateException("Aucune facture trouvee pour billingMonth=%s, sellerCompanyName=%s, invoiceName=%s"
                    .formatted(request.billingMonth(), request.sellerCompanyName(), request.invoiceName()));
        }

        List<SimpleInvoiceRequest> invoices = entities.stream().map(this::toSimpleInvoiceRequest).toList();
        String baseFilename = lookupFileSafeName(request, invoices);
        byte[] pdfBytes = buildCombinedPdf(invoices);
        byte[] excelBytes = buildSummaryExcel(invoices);

        Path pdfPath = storagePath.resolve(baseFilename + ".pdf");
        Path excelPath = storagePath.resolve(baseFilename + ".xlsx");
        Files.write(pdfPath, pdfBytes);
        Files.write(excelPath, excelBytes);

        return new GeneratedInvoiceFiles(invoices.get(0), pdfPath, excelPath, pdfBytes, excelBytes);
    }

    @Transactional
    public int deleteFromDb(InvoiceLookupRequest request) throws IOException {
        UUID tenantId = TenantContext.getTenantId();
        List<InvoiceEntity> toDelete = findInvoiceEntities(tenantId, request);
        if (toDelete.isEmpty()) {
            return 0;
        }
        invoiceRepo.deleteAll(toDelete);
        if (request.invoiceName() != null && !request.invoiceName().isBlank()) {
            String baseFilename = request.invoiceName().replaceAll("[^A-Za-z0-9_-]", "_");
            Files.deleteIfExists(storagePath.resolve(baseFilename + ".pdf"));
            Files.deleteIfExists(storagePath.resolve(baseFilename + ".xlsx"));
        }
        return toDelete.size();
    }

    public List<SimpleInvoiceRequest> findInvoicesByPeriod(String start, String end, String company, String consultantEmail) {
        return findEntitiesByPeriod(start, end, company, consultantEmail)
                .stream().map(this::toSimpleInvoiceRequest).toList();
    }

    /** Version enrichie incluant id + champs de suivi paiement. */
    public List<Map<String, Object>> findInvoicesByPeriodEnriched(String start, String end, String company, String consultantEmail) {
        LocalDate today = LocalDate.now();
        return findEntitiesByPeriod(start, end, company, consultantEmail).stream()
                .map(e -> {
                    // Calculer EN_RETARD dynamiquement
                    String status = e.getPaymentStatus() != null ? e.getPaymentStatus() : "EN_ATTENTE";
                    if (!"PAYEE".equals(status) && e.getPaymentDueDate() != null && e.getPaymentDueDate().isBefore(today)) {
                        status = "EN_RETARD";
                    }
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id",                  e.getId());
                    m.put("invoiceName",         e.getInvoiceName());
                    m.put("invoiceNumber",       e.getInvoiceNumber());
                    m.put("billingMonth",        e.getBillingMonth());
                    m.put("invoiceDate",         e.getInvoiceDate());
                    m.put("sellerCompanyName",   e.getSellerCompanyName());
                    m.put("clientCompanyName",   e.getClientCompanyName());
                    m.put("consultantEmail",     e.getConsultantEmail());
                    m.put("totalHt",             e.getTotalHt());
                    m.put("totalTtc",            e.getTotalTtc());
                    m.put("currency",            e.getCurrency());
                    m.put("paymentDueDate",      e.getPaymentDueDate());
                    m.put("paymentStatus",       status);
                    m.put("paymentReceivedDate", e.getPaymentReceivedDate());
                    m.put("sentDate",            e.getSentDate());
                    m.put("daysCount",           e.getDaysExact());
                    m.put("unitPriceHt",         e.getUnitPriceHt());
                    return m;
                })
                .toList();
    }

    private List<InvoiceEntity> findEntitiesByPeriod(String start, String end, String company, String consultantEmail) {
        UUID tenantId = TenantContext.getTenantId();
        List<InvoiceEntity> entities;
        if (!isBlank(company)) {
            entities = invoiceRepo.findByTenantIdAndBillingMonthBetweenAndSellerCompanyNameIgnoreCase(
                    tenantId, start != null ? start : "0000-00", end != null ? end : "9999-99", company);
        } else {
            entities = invoiceRepo.findByTenantIdAndBillingMonthBetween(
                    tenantId, start != null ? start : "0000-00", end != null ? end : "9999-99");
        }
        return entities.stream()
                .filter(e -> isBlank(consultantEmail)
                        || isBlank(e.getConsultantEmail())
                        || e.getConsultantEmail().trim().equalsIgnoreCase(consultantEmail.trim()))
                .sorted(Comparator.comparing(e -> e.getBillingMonth() != null ? e.getBillingMonth() : "", String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    // -----------------------------------------------------------------------
    // Entity / DTO conversion
    // -----------------------------------------------------------------------

    private InvoiceEntity toEntity(SimpleInvoiceRequest inv, UUID tenantId, String sourceText, String pdfPath, String excelPath) {
        InvoiceEntity e = new InvoiceEntity();
        e.setTenantId(tenantId);
        e.setInvoiceName(inv.invoiceName());
        e.setInvoiceDate(inv.invoiceDate());
        e.setBillingMonth(inv.billingMonth());
        e.setSellerCompanyName(inv.sellerCompanyName());
        e.setSellerAddress(inv.sellerAddress());
        e.setSellerRcs(inv.sellerRcs());
        e.setClientCompanyName(inv.clientCompanyName());
        e.setClientAddress(inv.clientAddress());
        e.setClientRcs(inv.clientRcs());
        e.setInvoiceTitle(inv.invoiceTitle());
        e.setDaysCount(inv.daysCount() != null ? (int) Math.round(inv.daysCount()) : null);
        e.setDaysExact(inv.daysCount() != null ? BigDecimal.valueOf(inv.daysCount()) : null);
        e.setUnitPriceHt(inv.unitPriceHt());
        e.setTotalHt(inv.totalHt());
        e.setVatRate(inv.vatRate());
        e.setTotalTtc(inv.totalTtc());
        e.setCurrency(inv.currency());
        e.setPaymentDueDate(inv.paymentDueDate());
        e.setLatePaymentClause(inv.latePaymentClause());
        e.setNotes(inv.notes());
        e.setConsultantEmail(inv.consultantEmail() != null ? inv.consultantEmail().toLowerCase().trim() : null);
        e.setSourceText(sourceText);
        e.setPdfPath(pdfPath);
        e.setExcelPath(excelPath);
        return e;
    }

    private SimpleInvoiceRequest toSimpleInvoiceRequest(InvoiceEntity e) {
        Double days = e.getDaysExact() != null ? e.getDaysExact().doubleValue()
                : (e.getDaysCount() != null ? (double) e.getDaysCount() : null);
        return new SimpleInvoiceRequest(
                e.getInvoiceName(),
                e.getInvoiceDate(),
                e.getBillingMonth(),
                e.getSellerCompanyName(),
                e.getSellerAddress(),
                e.getSellerRcs(),
                e.getClientCompanyName(),
                e.getClientAddress(),
                e.getClientRcs(),
                e.getInvoiceTitle(),
                days,
                e.getUnitPriceHt(),
                e.getTotalHt(),
                e.getVatRate(),
                e.getTotalTtc(),
                e.getCurrency(),
                e.getPaymentDueDate(),
                e.getLatePaymentClause(),
                e.getNotes(),
                null,
                e.getConsultantEmail(),
                null  // projectName — résolu dans normalize()
        );
    }

    private List<InvoiceEntity> findInvoiceEntities(UUID tenantId, InvoiceLookupRequest request) {
        if (request == null || isBlank(request.sellerCompanyName())) {
            return List.of();
        }
        if (!isBlank(request.invoiceName())) {
            return invoiceRepo.findByTenantIdAndInvoiceNameIgnoreCaseAndSellerCompanyNameIgnoreCase(
                    tenantId, request.invoiceName(), request.sellerCompanyName());
        }
        if (!isBlank(request.billingMonth())) {
            return invoiceRepo.findByTenantIdAndBillingMonthAndSellerCompanyNameIgnoreCase(
                    tenantId, request.billingMonth(), request.sellerCompanyName());
        }
        return List.of();
    }

    // -----------------------------------------------------------------------
    // Normalize
    // -----------------------------------------------------------------------

    private SimpleInvoiceRequest normalize(SimpleInvoiceRequest request) {
        String billingMonth = resolveBillingMonth(request, request.invoiceDate());
        LocalDate invoiceDate = resolveInvoiceDate(request, billingMonth);
        BigDecimal vatRate = request.vatRate() != null ? request.vatRate() : DEFAULT_VAT_RATE;
        String currency = isBlank(request.currency()) ? DEFAULT_CURRENCY : request.currency();
        String title = isBlank(request.invoiceTitle()) ? DEFAULT_TITLE : request.invoiceTitle();
        LocalDate dueDate = request.paymentDueDate() != null ? request.paymentDueDate()
                : resolvePaymentDueDate(invoiceDate, request.clientCompanyName(), request.consultantEmail(), TenantContext.getTenantId());

        Double daysCount = resolveDaysCount(request, billingMonth);

        BigDecimal totalHt = request.totalHt();
        if (daysCount != null && request.unitPriceHt() != null) {
            totalHt = request.unitPriceHt()
                    .multiply(BigDecimal.valueOf(daysCount))
                    .setScale(2, RoundingMode.HALF_UP);
        } else if (totalHt != null) {
            totalHt = totalHt.setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal totalTtc = request.totalTtc();
        if (totalHt != null) {
            totalTtc = totalHt.multiply(BigDecimal.ONE.add(vatRate)).setScale(2, RoundingMode.HALF_UP);
        } else if (totalTtc != null) {
            totalTtc = totalTtc.setScale(2, RoundingMode.HALF_UP);
        }

        String invoiceName = resolveInvoiceName(request, billingMonth);

        String latePaymentClause = request.latePaymentClause();
        if (isBlank(latePaymentClause)) {
            SellerProfile profile = findSellerProfile(request.sellerCompanyName());
            latePaymentClause = (profile != null && !isBlank(profile.latePaymentClause()))
                    ? profile.latePaymentClause()
                    : DEFAULT_LATE_PAYMENT_CLAUSE;
        }

        String projectName = !isBlank(request.projectName()) ? request.projectName()
                : findProjectName(request.consultantEmail(), TenantContext.getTenantId());

        return new SimpleInvoiceRequest(
                invoiceName,
                invoiceDate,
                billingMonth,
                request.sellerCompanyName(),
                request.sellerAddress(),
                request.sellerRcs(),
                request.clientCompanyName(),
                request.clientAddress(),
                request.clientRcs(),
                title,
                daysCount,
                scale(request.unitPriceHt()),
                totalHt,
                vatRate.setScale(2, RoundingMode.HALF_UP),
                totalTtc,
                currency,
                dueDate,
                latePaymentClause,
                request.notes(),
                null,
                request.consultantEmail(),
                projectName
        );
    }

    private String findProjectName(String consultantEmail, UUID tenantId) {
        if (isBlank(consultantEmail) || tenantId == null) return null;
        try {
            List<?> rows = jdbcTemplate.queryForList(
                    "SELECT p.name FROM project p " +
                    "JOIN consultant_project cp ON cp.project_id = p.id " +
                    "JOIN consultant_profile c ON c.id = cp.consultant_profile_id " +
                    "WHERE LOWER(c.email) = LOWER(?) AND c.tenant_id = ? LIMIT 1",
                    consultantEmail.trim(), tenantId);
            if (!rows.isEmpty()) {
                Object row = rows.get(0);
                if (row instanceof java.util.Map<?,?> m) {
                    Object val = m.values().iterator().next();
                    return val != null ? val.toString() : null;
                }
            }
        } catch (Exception ignored) { /* non bloquant */ }
        return null;
    }

    private SellerProfile findSellerProfile(String companyName) {
        if (isBlank(companyName)) return null;
        UUID tenantId = TenantContext.getTenantId();
        return sellerProfileRepo.findByTenantIdAndCompanyNameIgnoreCase(tenantId, companyName)
                .map(e -> new SellerProfile(e.getCompanyName(), e.getAddress(), e.getRcs(),
                        e.getIban(), e.getBic(), e.getEmail(), e.getCapital(), e.getLatePaymentClause()))
                .orElse(null);
    }

    private LocalDate resolvePaymentDueDate(LocalDate invoiceDate, String clientCompanyName, String consultantEmail, UUID tenantId) {
        if (!isBlank(clientCompanyName) && !isBlank(consultantEmail) && tenantId != null) {
            try {
                Integer days = jdbcTemplate.queryForObject(
                        "SELECT ca.payment_terms_days " +
                        "FROM consultant_assignment ca " +
                        "JOIN consultant_profile cp ON cp.id = ca.consultant_profile_id " +
                        "JOIN client c ON c.id = ca.client_id " +
                        "WHERE LOWER(c.name) = LOWER(?) AND LOWER(cp.email) = LOWER(?) AND ca.tenant_id = ? " +
                        "LIMIT 1",
                        Integer.class, clientCompanyName.trim(), consultantEmail.trim(), tenantId);
                if (days != null) {
                    return invoiceDate.plusDays(days);
                }
            } catch (Exception ignored) { /* assignment non trouvé — défaut appliqué */ }
        }
        return invoiceDate.plusDays(30);
    }

    private Double resolveDaysCount(SimpleInvoiceRequest request, String billingMonth) {
        List<AbsencePeriod> absences = request.absencePeriods();
        if (absences == null || absences.isEmpty()) {
            return request.daysCount();
        }
        if (billingMonth == null) {
            return request.daysCount();
        }
        YearMonth ym = YearMonth.parse(billingMonth);
        Set<LocalDate> absentDates = resolveAbsentDates(absences);
        Set<MonthDay> holidays = frenchFixedHolidays();
        int count = 0;
        for (int d = 1; d <= ym.lengthOfMonth(); d++) {
            LocalDate date = ym.atDay(d);
            DayOfWeek dow = date.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) continue;
            if (holidays.contains(MonthDay.from(date))) continue;
            if (absentDates.contains(date)) continue;
            count++;
        }
        return (double) count;
    }

    private Set<LocalDate> resolveAbsentDates(List<AbsencePeriod> periods) {
        Set<LocalDate> absent = new HashSet<>();
        for (AbsencePeriod p : periods) {
            if (p.getFrom() == null || p.getTo() == null) continue;
            try {
                LocalDate from = LocalDate.parse(p.getFrom());
                LocalDate to   = LocalDate.parse(p.getTo());
                LocalDate d = from;
                while (!d.isAfter(to)) { absent.add(d); d = d.plusDays(1); }
            } catch (Exception e) {
                // date invalide — ignoree silencieusement
            }
        }
        return absent;
    }

    private Set<MonthDay> frenchFixedHolidays() {
        return Set.of(
            MonthDay.of(1,  1),
            MonthDay.of(5,  1),
            MonthDay.of(5,  8),
            MonthDay.of(7,  14),
            MonthDay.of(8,  15),
            MonthDay.of(11, 1),
            MonthDay.of(11, 11),
            MonthDay.of(12, 25)
        );
    }

    private String resolveBillingMonth(SimpleInvoiceRequest request, LocalDate invoiceDate) {
        if (!isBlank(request.billingMonth())) {
            return request.billingMonth();
        }
        LocalDate referenceDate = invoiceDate != null ? invoiceDate : LocalDate.now();
        return YearMonth.from(referenceDate).toString();
    }

    private LocalDate resolveInvoiceDate(SimpleInvoiceRequest request, String billingMonth) {
        if (request.invoiceDate() != null) {
            return request.invoiceDate();
        }
        if (!isBlank(billingMonth)) {
            return YearMonth.parse(billingMonth).plusMonths(1).atDay(1);
        }
        return LocalDate.now().withDayOfMonth(1);
    }

    private String resolveInvoiceName(SimpleInvoiceRequest request, String billingMonth) {
        if (!isBlank(request.invoiceName())) {
            return request.invoiceName();
        }
        if (isBlank(request.sellerCompanyName()) || isBlank(billingMonth)) {
            return null;
        }

        UUID tenantId = TenantContext.getTenantId();
        String yyyymm = YearMonth.parse(billingMonth).plusMonths(1).toString().replace("-", "");
        String expectedPrefix = "F-" + yyyymm + "-";

        List<InvoiceEntity> existingInvoices = invoiceRepo.findByTenantIdAndBillingMonthAndSellerCompanyNameIgnoreCase(
                tenantId, billingMonth, request.sellerCompanyName());
        int nextSequence = existingInvoices.stream()
                .map(InvoiceEntity::getInvoiceName)
                .filter(name -> name != null && name.toUpperCase().startsWith(expectedPrefix.toUpperCase()))
                .mapToInt(this::extractSequenceNumber)
                .max()
                .orElse(0) + 1;

        return "F-%s-%02d".formatted(yyyymm, nextSequence);
    }

    private int extractSequenceNumber(String invoiceName) {
        if (isBlank(invoiceName)) {
            return 0;
        }
        int lastDash = invoiceName.lastIndexOf('-');
        if (lastDash < 0 || lastDash == invoiceName.length() - 1) {
            return 0;
        }
        try {
            return Integer.parseInt(invoiceName.substring(lastDash + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private byte[] buildPdf(SimpleInvoiceRequest invoice) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                renderInvoice(content, invoice);
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private byte[] buildCombinedPdf(List<SimpleInvoiceRequest> invoices) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (SimpleInvoiceRequest invoice : invoices) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    renderInvoice(content, invoice);
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private byte[] buildExcel(SimpleInvoiceRequest invoice) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Facture");
            CellStyle title = titleStyle(workbook);
            CellStyle header = headerStyle(workbook);
            CellStyle text = cellStyle(workbook, HorizontalAlignment.LEFT, false);
            CellStyle amount = cellStyle(workbook, HorizontalAlignment.RIGHT, false);
            CellStyle total = cellStyle(workbook, HorizontalAlignment.RIGHT, true);
            SellerProfile sellerProfile = findSellerProfile(invoice.sellerCompanyName());
            String sellerAddr = isBlank(invoice.sellerAddress()) && sellerProfile != null
                    ? safe(sellerProfile.address()) : safe(invoice.sellerAddress());
            String sellerRcs  = isBlank(invoice.sellerRcs()) && sellerProfile != null
                    ? safe(sellerProfile.rcs()) : safe(invoice.sellerRcs());

            int rowIdx = 0;
            rowIdx = mergedValueRow(sheet, rowIdx, 0, 3, "FACTURE", title);
            rowIdx = writeGridRow(sheet, rowIdx, "Emetteur", "Client", header);
            rowIdx = writeGridRow(sheet, rowIdx, safe(invoice.sellerCompanyName()), safe(invoice.clientCompanyName()), text);
            rowIdx = writeGridRow(sheet, rowIdx, sellerAddr, safe(invoice.clientAddress()), text);
            rowIdx = writeGridRow(sheet, rowIdx, sellerRcs,  safe(invoice.clientRcs()), text);
            rowIdx++;
            rowIdx = writeKeyValueRow(sheet, rowIdx, "Facture N\u00b0", safe(invoice.invoiceName()), text);
            rowIdx = writeKeyValueRow(sheet, rowIdx, "Date", safeDate(invoice.invoiceDate()), text);
            rowIdx = writeKeyValueRow(sheet, rowIdx, "Echeance", safeDate(invoice.paymentDueDate()), text);
            rowIdx = writeKeyValueRow(sheet, rowIdx, "Mois", safe(invoice.billingMonth()), text);
            rowIdx++;
            Row tableHeader = sheet.createRow(rowIdx++);
            writeCell(tableHeader, 0, "Designation", header);
            writeCell(tableHeader, 1, "Quantite", header);
            writeCell(tableHeader, 2, "Prix unitaire HT", header);
            writeCell(tableHeader, 3, "Montant total HT", header);
            Row line = sheet.createRow(rowIdx++);
            String excelTitle = safe(invoice.invoiceTitle()) +
                    (!isBlank(invoice.projectName()) ? " - " + safe(invoice.projectName()) : "");
            writeCell(line, 0, excelTitle, text);
            writeCell(line, 1, safeNumber(invoice.daysCount()), amount);
            writeCell(line, 2, money(invoice.unitPriceHt(), invoice.currency()), amount);
            writeCell(line, 3, money(invoice.totalHt(), invoice.currency()), amount);
            rowIdx++;
            rowIdx = writeAmountRow(sheet, rowIdx, "Total HT", money(invoice.totalHt(), invoice.currency()), amount);
            rowIdx = writeAmountRow(sheet, rowIdx, "TVA 20%", money(vatAmount(invoice), invoice.currency()), amount);
            rowIdx = writeAmountRow(sheet, rowIdx, "Total TTC", money(invoice.totalTtc(), invoice.currency()), total);
            rowIdx++;
            rowIdx = mergedValueRow(sheet, rowIdx, 0, 3, safe(invoice.latePaymentClause()), text);
            if (sellerProfile != null) {
                rowIdx++;
                rowIdx = writeKeyValueRow(sheet, rowIdx, "IBAN", safe(sellerProfile.iban()), text);
                rowIdx = writeKeyValueRow(sheet, rowIdx, "BIC", safe(sellerProfile.bic()), text);
                rowIdx = mergedValueRow(sheet, rowIdx, 0, 3,
                        safe(sellerProfile.companyName()) + " - capital " + safe(sellerProfile.capital()), text);
                rowIdx = mergedValueRow(sheet, rowIdx, 0, 3, safe(sellerProfile.address()), text);
                rowIdx = mergedValueRow(sheet, rowIdx, 0, 3, safe(sellerProfile.email()), text);
            }
            if (!isBlank(invoice.notes())) {
                rowIdx++;
                mergedValueRow(sheet, rowIdx, 0, 3, invoice.notes(), text);
            }

            for (int i = 0; i < 4; i++) {
                sheet.autoSizeColumn(i);
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private byte[] buildSummaryExcel(List<SimpleInvoiceRequest> invoices) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Factures");
            CellStyle headerCellStyle = headerStyle(workbook);
            CellStyle text = cellStyle(workbook, HorizontalAlignment.LEFT, false);
            CellStyle amount = cellStyle(workbook, HorizontalAlignment.RIGHT, false);
            int rowIdx = 0;
            Row headerRow = sheet.createRow(rowIdx++);
            writeCell(headerRow, 0, "Facture", headerCellStyle);
            writeCell(headerRow, 1, "Date", headerCellStyle);
            writeCell(headerRow, 2, "Mois", headerCellStyle);
            writeCell(headerRow, 3, "Societe emettrice", headerCellStyle);
            writeCell(headerRow, 4, "Societe cliente", headerCellStyle);
            writeCell(headerRow, 5, "Jours", headerCellStyle);
            writeCell(headerRow, 6, "Prix HT / jour", headerCellStyle);
            writeCell(headerRow, 7, "Total HT", headerCellStyle);
            writeCell(headerRow, 8, "Total TTC", headerCellStyle);
            writeCell(headerRow, 9, "Echeance", headerCellStyle);

            for (SimpleInvoiceRequest invoice : invoices) {
                Row row = sheet.createRow(rowIdx++);
                writeCell(row, 0, safe(invoice.invoiceName()), text);
                writeCell(row, 1, safeDate(invoice.invoiceDate()), text);
                writeCell(row, 2, safe(invoice.billingMonth()), text);
                writeCell(row, 3, safe(invoice.sellerCompanyName()), text);
                writeCell(row, 4, safe(invoice.clientCompanyName()), text);
                writeCell(row, 5, safeNumber(invoice.daysCount()), amount);
                writeCell(row, 6, money(invoice.unitPriceHt(), invoice.currency()), amount);
                writeCell(row, 7, money(invoice.totalHt(), invoice.currency()), amount);
                writeCell(row, 8, money(invoice.totalTtc(), invoice.currency()), amount);
                writeCell(row, 9, safeDate(invoice.paymentDueDate()), text);
            }

            for (int i = 0; i < 10; i++) {
                sheet.autoSizeColumn(i);
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private void renderInvoice(PDPageContentStream content, SimpleInvoiceRequest invoice) throws IOException {
        float pageWidth = PDRectangle.A4.getWidth();
        float left = 55;
        float right = pageWidth - 55;
        float middle = pageWidth / 2;

        SellerProfile sellerProfile = findSellerProfile(invoice.sellerCompanyName());
        String sellerAddr = isBlank(invoice.sellerAddress()) && sellerProfile != null
                ? safe(sellerProfile.address()) : safe(invoice.sellerAddress());
        String sellerRcs  = isBlank(invoice.sellerRcs()) && sellerProfile != null
                ? safe(sellerProfile.rcs()) : safe(invoice.sellerRcs());

        writeText(content, left, 800, PDType1Font.HELVETICA_BOLD, 18, "FACTURE");
        drawRightAlignedText(content, PDType1Font.HELVETICA, 10, right, 802, formatDisplayDate(invoice.invoiceDate()));

        drawRect(content, left, 700, 220, 80);
        drawRect(content, middle + 10, 700, 220, 80);
        writeText(content, left + 10, 762, PDType1Font.HELVETICA_BOLD, 11, "EMETTEUR");
        writeText(content, middle + 20, 762, PDType1Font.HELVETICA_BOLD, 11, "CLIENT");
        writeMultiline(content, left + 10, 744, PDType1Font.HELVETICA, 10, buildPartyBlock(invoice.sellerCompanyName(), sellerAddr, sellerRcs), 34);
        writeMultiline(content, middle + 20, 744, PDType1Font.HELVETICA, 10, buildPartyBlock(invoice.clientCompanyName(), invoice.clientAddress(), invoice.clientRcs()), 34);

        drawRect(content, left, 610, right - left, 78);
        writeMeta(content, left + 12, 664, "Numero de facture", safe(invoice.invoiceName()), right - 12);
        writeMeta(content, left + 12, 646, "Date de facture", safeDate(invoice.invoiceDate()), right - 12);
        writeMeta(content, left + 12, 628, "Date d'echeance", safeDate(invoice.paymentDueDate()), right - 12);

        float[] cols = new float[]{left, left + 250, left + 330, left + 420, right};
        drawTable(content, 560, 24, cols);
        shadeRow(content, 536, right - left, left);
        writeCentered(content, cols[0], cols[1], 544, "Designation");
        writeCentered(content, cols[1], cols[2], 544, "Quantite");
        writeCentered(content, cols[2], cols[3], 544, "Prix HT");
        writeCentered(content, cols[3], cols[4], 544, "Montant total HT");
        drawTable(content, 536, 28, cols);
        String pdfTitle = safe(invoice.invoiceTitle()) +
                (!isBlank(invoice.projectName()) ? " - " + safe(invoice.projectName()) : "");
        writeText(content, cols[0] + 8, 518, PDType1Font.HELVETICA, 10, pdfTitle);
        drawRightAlignedText(content, PDType1Font.HELVETICA, 10, cols[2] - 8, 518, safeNumber(invoice.daysCount()));
        drawRightAlignedText(content, PDType1Font.HELVETICA, 10, cols[3] - 8, 518, money(invoice.unitPriceHt(), invoice.currency()));
        drawRightAlignedText(content, PDType1Font.HELVETICA, 10, cols[4] - 12, 518, money(invoice.totalHt(), invoice.currency()));

        writeSummary(content, right - 170, 455, "Total HT", money(invoice.totalHt(), invoice.currency()));
        writeSummary(content, right - 170, 433, "TVA 20%", money(vatAmount(invoice), invoice.currency()));
        drawRect(content, right - 175, 395, 175, 24);
        writeText(content, right - 165, 403, PDType1Font.HELVETICA_BOLD, 11, "Total TTC");
        drawRightAlignedText(content, PDType1Font.HELVETICA_BOLD, 11, right - 10, 403, money(invoice.totalTtc(), invoice.currency()));

        writeText(content, left, 355, PDType1Font.HELVETICA_BOLD, 10, "Conditions de paiement");
        float clauseEndY = writeMultiline(content, left, 338, PDType1Font.HELVETICA_OBLIQUE, 9, invoice.latePaymentClause(), 110);

        float afterTextY = clauseEndY;
        if (!isBlank(invoice.notes())) {
            float notesStartY = clauseEndY - 16;
            afterTextY = writeMultiline(content, left, notesStartY, PDType1Font.HELVETICA, 9, invoice.notes(), 110);
        }

        if (sellerProfile != null) {
            float ribTop = afterTextY - 24;
            float ribLeft = left + (right - left - 420f) / 2f;
            drawSellerProfileBlock(content, ribLeft, ribTop, sellerProfile);
        }
    }

    // -----------------------------------------------------------------------
    // Excel helpers
    // -----------------------------------------------------------------------

    private int mergedValueRow(Sheet sheet, int rowIdx, int startCol, int endCol, String value, CellStyle style) {
        Row row = sheet.createRow(rowIdx);
        for (int col = startCol; col <= endCol; col++) {
            writeCell(row, col, col == startCol ? value : "", style);
        }
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(rowIdx, rowIdx, startCol, endCol));
        return rowIdx + 1;
    }

    private int writeGridRow(Sheet sheet, int rowIdx, String leftValue, String rightValue, CellStyle style) {
        Row row = sheet.createRow(rowIdx);
        writeCell(row, 0, leftValue, style);
        writeCell(row, 1, "", style);
        writeCell(row, 2, rightValue, style);
        writeCell(row, 3, "", style);
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(rowIdx, rowIdx, 0, 1));
        sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(rowIdx, rowIdx, 2, 3));
        return rowIdx + 1;
    }

    private int writeKeyValueRow(Sheet sheet, int rowIdx, String key, String value, CellStyle style) {
        Row row = sheet.createRow(rowIdx);
        writeCell(row, 0, key, style);
        writeCell(row, 1, value, style);
        return rowIdx + 1;
    }

    private int writeAmountRow(Sheet sheet, int rowIdx, String key, String value, CellStyle style) {
        Row row = sheet.createRow(rowIdx);
        writeCell(row, 2, key, style);
        writeCell(row, 3, value, style);
        return rowIdx + 1;
    }

    private void writeCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    // -----------------------------------------------------------------------
    // PDF helpers
    // -----------------------------------------------------------------------

    private float writeLine(PDPageContentStream content, float x, float y, PDFont font, int size, String text) throws IOException {
        writeText(content, x, y, font, size, text);
        return y - (size + 4);
    }

    private void writeText(PDPageContentStream content, float x, float y, PDFont font, int size, String text) throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(text == null ? "" : text);
        content.endText();
    }

    private float writeMultiline(PDPageContentStream content, float x, float startY, PDFont font, int size, String text, int maxChars) throws IOException {
        float y = startY;
        String value = text == null ? "" : text.replace("\r", "");
        for (String paragraph : value.split("\n")) {
            String remaining = paragraph.trim();
            if (remaining.isEmpty()) {
                y -= (size + 4);
                continue;
            }
            while (!remaining.isBlank()) {
                String line = remaining.length() <= maxChars ? remaining : remaining.substring(0, maxChars);
                int split = line.length() == remaining.length() ? line.length() : Math.max(line.lastIndexOf(' '), 1);
                String rendered = remaining.substring(0, split).trim();
                y = writeLine(content, x, y, font, size, rendered);
                remaining = remaining.substring(split).trim();
            }
        }
        return y;
    }

    private void drawRect(PDPageContentStream content, float x, float y, float width, float height) throws IOException {
        content.addRect(x, y, width, height);
        content.stroke();
    }

    private void drawTable(PDPageContentStream content, float topY, float height, float[] cols) throws IOException {
        content.addRect(cols[0], topY - height, cols[cols.length - 1] - cols[0], height);
        for (float col : cols) {
            content.moveTo(col, topY);
            content.lineTo(col, topY - height);
        }
        content.stroke();
    }

    private void shadeRow(PDPageContentStream content, float y, float width, float leftX) throws IOException {
        content.setNonStrokingColor(235, 235, 235);
        content.addRect(leftX, y, width, 24);
        content.fill();
        content.setNonStrokingColor(0, 0, 0);
    }

    private void writeCentered(PDPageContentStream content, float startX, float endX, float y, String text) throws IOException {
        String value = text == null ? "" : text;
        float width = PDType1Font.HELVETICA_BOLD.getStringWidth(value) / 1000 * 9;
        float centerX = startX + ((endX - startX) / 2);
        writeText(content, centerX - (width / 2), y, PDType1Font.HELVETICA_BOLD, 9, value);
    }

    private void writeMeta(PDPageContentStream content, float x, float y, String label, String value, float rightX) throws IOException {
        writeText(content, x, y, PDType1Font.HELVETICA_BOLD, 10, label);
        drawRightAlignedText(content, PDType1Font.HELVETICA, 10, rightX, y, value);
    }

    private void writeSummary(PDPageContentStream content, float x, float y, String label, String value) throws IOException {
        writeText(content, x, y, PDType1Font.HELVETICA, 10, label);
        drawRightAlignedText(content, PDType1Font.HELVETICA, 10, x + 160, y, value);
    }

    private void drawRightAlignedText(PDPageContentStream content, PDFont font, int size, float rightX, float y, String text) throws IOException {
        String value = text == null ? "" : text;
        float width = font.getStringWidth(value) / 1000 * size;
        writeText(content, rightX - width, y, font, size, value);
    }

    private void drawSellerProfileBlock(PDPageContentStream content, float left, float topY, SellerProfile profile) throws IOException {
        float width = 420;
        float[] cols = new float[]{left, left + 285, left + width};
        shadeRow(content, topY - 18, width, left);
        drawTable(content, topY, 18, cols);
        writeCentered(content, cols[0], cols[1], topY - 12, "IBAN");
        writeCentered(content, cols[1], cols[2], topY - 12, "BIC");

        drawTable(content, topY - 18, 18, cols);
        writeCenteredValue(content, cols[0], cols[1], topY - 30, safe(profile.iban()));
        writeCenteredValue(content, cols[1], cols[2], topY - 30, safe(profile.bic()));

        drawCenteredValue(content, left, left + width, topY - 50, safe(profile.companyName()) + " au capital de " + safe(profile.capital()), PDType1Font.HELVETICA_BOLD, 8);
        drawCenteredValue(content, left, left + width, topY - 62, safe(profile.address()), PDType1Font.HELVETICA_BOLD, 8);
        drawCenteredValue(content, left, left + width, topY - 74, safe(profile.email()), PDType1Font.HELVETICA_BOLD, 8);
    }

    private void writeCenteredValue(PDPageContentStream content, float startX, float endX, float y, String text) throws IOException {
        drawCenteredValue(content, startX, endX, y, text, PDType1Font.HELVETICA, 8);
    }

    private void drawCenteredValue(PDPageContentStream content, float startX, float endX, float y, String text, PDFont font, int size) throws IOException {
        String value = text == null ? "" : text;
        float width = font.getStringWidth(value) / 1000 * size;
        float centerX = startX + ((endX - startX) / 2);
        writeText(content, centerX - (width / 2), y, font, size, value);
    }

    private CellStyle titleStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 14);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setFillForegroundColor((short) 22);
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle cellStyle(Workbook workbook, HorizontalAlignment alignment, boolean bold) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(bold);
        style.setFont(font);
        style.setAlignment(alignment);
        style.setWrapText(true);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    // -----------------------------------------------------------------------
    // Value helpers
    // -----------------------------------------------------------------------

    private String fileSafeName(SimpleInvoiceRequest invoice) {
        String invoiceName = invoice.invoiceName();
        if (!isBlank(invoiceName)) {
            return invoiceName.replaceAll("[^A-Za-z0-9_-]", "_");
        }
        return "invoice-" + invoice.invoiceDate().format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private String lookupFileSafeName(InvoiceLookupRequest request, List<SimpleInvoiceRequest> invoices) {
        if (invoices.size() == 1 && !isBlank(invoices.get(0).invoiceName())) {
            return fileSafeName(invoices.get(0));
        }
        String seller = request.sellerCompanyName() == null ? "seller" : request.sellerCompanyName().replaceAll("[^A-Za-z0-9_-]", "_");
        return "invoices-" + request.billingMonth().replaceAll("[^0-9-]", "") + "-" + seller;
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String safeDate(LocalDate value) {
        return value == null ? "" : value.toString();
    }

    private String safeNumber(Double value) {
        if (value == null) return "";
        return value == Math.floor(value) ? String.valueOf(value.longValue()) : String.valueOf(value);
    }

    private String money(BigDecimal amount, String currency) {
        if (amount == null) {
            return "";
        }
        String formatted = amount.setScale(2, RoundingMode.HALF_UP).toString().replace(".", ",");
        return formatted + " \u20ac";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private BigDecimal vatAmount(SimpleInvoiceRequest invoice) {
        if (invoice.totalHt() == null || invoice.vatRate() == null) {
            return null;
        }
        return invoice.totalHt().multiply(invoice.vatRate()).setScale(2, RoundingMode.HALF_UP);
    }

    private String buildPartyBlock(String company, String address, String rcs) {
        StringBuilder builder = new StringBuilder();
        if (!isBlank(company)) builder.append(company);
        if (!isBlank(address)) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(address);
        }
        if (!isBlank(rcs)) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(rcs);
        }
        return builder.toString();
    }

    private String formatDisplayDate(LocalDate date) {
        if (date == null) {
            return "";
        }
        String month = date.getMonth().getDisplayName(TextStyle.FULL, FRENCH);
        return "%d %s %d".formatted(date.getDayOfMonth(), capitalize(month), date.getYear());
    }

    private String capitalize(String value) {
        if (isBlank(value)) {
            return "";
        }
        return value.substring(0, 1).toUpperCase(FRENCH) + value.substring(1);
    }

    public record GeneratedInvoiceFiles(
            SimpleInvoiceRequest invoice,
            Path pdfPath,
            Path excelPath,
            byte[] pdfBytes,
            byte[] excelBytes
    ) {}
}
