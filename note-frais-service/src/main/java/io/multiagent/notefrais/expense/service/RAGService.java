package io.multiagent.notefrais.expense.service;

import io.multiagent.notefrais.client.LLMAIClient;
import io.multiagent.notefrais.expense.repository.ExpenseWeaviateRepository;
import io.multiagent.notefrais.kafka.DomainEvent;
import io.multiagent.notefrais.kafka.EventPublisher;
import io.multiagent.notefrais.kafka.KafkaTopics;
import io.multiagent.notefrais.model.ExpenseItem;
import io.multiagent.notefrais.model.IntentResult;
import io.multiagent.notefrais.model.ReasoningResult;
import io.multiagent.notefrais.model.ExpenseReportResponse;
import io.multiagent.notefrais.reasoning.QueryRewriteService;
import io.multiagent.notefrais.reasoning.SemanticSearchService;
import io.multiagent.notefrais.reasoning.ReRankService;
import io.multiagent.notefrais.util.DateProvider;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates the full RAG flow (rewrite → search → rerank → reasoning LLM).
 * NotificationService dependency removed — uses Kafka EventPublisher for EXPENSE_CREATED events.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGService {

    private static final String PROCESSING_MS = "processingMs";
    private static final String PAYMENT_MODE_PERSONNEL = "Personnel";

    private final QueryRewriteService rewriteService;
    private final SemanticSearchService semanticSearch;
    private final ReRankService rerankService;
    private final LLMAIClient llm;
    private final DateProvider dateProvider;
    private final ExpenseReportService expenseReportService;
    private final ExpenseWeaviateRepository expenseRepo;
    private final ExpenseIdGenerator idGenerator;
    private final ExpensePdfService expensePdfService;

    // Kafka best-effort : null si le broker n'est pas disponible en dev local
    @Autowired(required = false)
    private EventPublisher eventPublisher;

    @Value("${ai-core.expense.km-rate-7cv:0.661}")
    private double kmRate;

    @Value("${ai-core.expense.km-annual:11000}")
    private int kmAnnual;

    @Value("${ai-core.company-name:}")
    private String defaultCompanyName;

    @Value("${AI_CORE_PROMPT_SINGLE_EXPENSE:}")
    private String singleExpensePromptEnv;

    @Value("${AI_CORE_PROMPT_EXPENSE_LIST:}")
    private String expenseListPromptEnv;

    @Value("${AI_CORE_PROMPT_INVOICE:}")
    private String invoicePromptEnv;

    private String singleExpensePromptTemplate;
    private String expenseListPromptTemplate;
    private String invoicePromptTemplate;

    @PostConstruct
    public void loadPrompts() {
        try {
            singleExpensePromptTemplate = resolvePrompt(singleExpensePromptEnv, "AI_CORE_PROMPT_SINGLE_EXPENSE");
            expenseListPromptTemplate = resolvePrompt(expenseListPromptEnv, "AI_CORE_PROMPT_EXPENSE_LIST");
            invoicePromptTemplate = resolvePrompt(invoicePromptEnv, "AI_CORE_PROMPT_INVOICE");
        } catch (Exception e) {
            throw new IllegalStateException("Impossible de charger les prompts RAG", e);
        }
    }

    private String resolvePrompt(String envValue, String envName) throws Exception {
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        throw new IllegalStateException("Le prompt " + envName + " doit être fourni via la ConfigMap/ENV");
    }

    // ===========================================================
    // 1. Pipeline : créer UNE note de frais
    // ===========================================================
    public ReasoningResult extractSingleExpense(String text, IntentResult intent) {
        return extractSingleExpense(text, intent, null);
    }

    public ReasoningResult extractSingleExpense(String text, IntentResult intent, String consultantEmail) {
        log.info("Pipeline: create_expense");
        long start = System.currentTimeMillis();

        String rewritten = rewriteService.rewrite(text);
        List<String> docs = semanticSearch.searchTopK(rewritten, 5);
        List<String> sortedDocs = rerankService.rerankAndExtractTopK(rewritten, docs, 3);

        String json = llm.extractJSON(
                singleExpensePromptTemplate.formatted(dateProvider.todayUtc().toString()),
                buildSingleExpenseUserPrompt(text, sortedDocs));
        List<ExpenseItem> items = ExpenseItem.fromJsonArray("[" + json + "]");
        if (items.isEmpty()) {
            log.error("Impossible d'extraire une dépense structurée du JSON retourné par le LLM pour le texte : {}", text);
            return ReasoningResult.error("Le LLM n'a pas pu extraire de dépense valide à partir du texte fourni.");
        }

        ExpenseItem expense = items.get(0);
        ReasoningResult validationError = prepareExpense(expense, text, consultantEmail);
        if (validationError != null) return validationError;

        List<ExpenseItem> expanded = isKmMonthly(expense, text) ? expandKmMonthly(expense, text) : List.of(expense);
        List<ExpenseItem> validExpenses = indexExpenses(expanded);

        return ReasoningResult.builder()
                .type("create_expense")
                .status("EXPENSE_CREATED")
                .confidence(intent.getConfidence())
                .raw(text)
                .expenses(validExpenses)
                .metadata(Map.of(
                        "rewrittenQuery", rewritten,
                        "candidateChunks", docs.size(),
                        "topSorted", sortedDocs.size(),
                        PROCESSING_MS, System.currentTimeMillis() - start
                ))
                .build();
    }

    /** Enrichit, normalise et valide une expense avant indexation. Retourne une erreur si invalide, null sinon. */
    private ReasoningResult prepareExpense(ExpenseItem expense, String text, String consultantEmail) {
        enrichFromText(expense, text);
        normalizeExpense(expense, text);
        enrichKm(expense, text);
        if (isBlank(expense.getPaymentMode())) expense.setPaymentMode(PAYMENT_MODE_PERSONNEL);
        if (!isBlank(consultantEmail)) {
            if (!consultantEmail.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                log.warn("consultantEmail invalide ignoré : format non conforme");
            } else {
                expense.setConsultantEmail(consultantEmail.toLowerCase(java.util.Locale.ROOT));
            }
        }
        if (isBlank(expense.getCompany()) && !isBlank(defaultCompanyName)) expense.setCompany(defaultCompanyName);
        if (isBlank(expense.getCompany())) {
            String msg = "Société/organisation manquante : indiquez le nom ou configurez AI_CORE_COMPANY_NAME";
            log.error(msg);
            return ReasoningResult.error(msg);
        }
        if (isBlank(expense.getCurrency())) expense.setCurrency("EUR");
        if (isBlank(expense.getAddress())) expense.setAddress("Paris, France");
        return null;
    }

    /** Génère les ids, valide et indexe chaque expense dans Weaviate. Publie sur Kafka. */
    private List<ExpenseItem> indexExpenses(List<ExpenseItem> expenses) {
        List<ExpenseItem> valid = new java.util.ArrayList<>();
        for (ExpenseItem exp : expenses) {
            assignId(exp);
            if (exp.getAmount() == null || isBlank(exp.getCurrency())
                    || isBlank(exp.getType()) || isBlank(exp.getPaymentMode()) || isBlank(exp.getAddress())) {
                log.warn("Expense invalide (champ obligatoire manquant) après extraction: {}", exp);
                continue;
            }
            try {
                expenseRepo.indexExpense(null, exp, "text", false, null);
                valid.add(exp);
                // Publish to Kafka (best-effort — no direct notification service call)
                if (eventPublisher != null) {
                    String consultantName = exp.getConsultantEmail() != null ? exp.getConsultantEmail() : "inconnu";
                    eventPublisher.publish(
                            KafkaTopics.EXPENSE_CREATED,
                            new DomainEvent(
                                    "EXPENSE_CREATED",
                                    String.valueOf(exp.getId()),
                                    exp.getConsultantEmail(),
                                    exp.getCompany(),
                                    "{\"type\":\"" + safeType(exp.getType()) + "\",\"amount\":" + exp.getAmount()
                                            + ",\"date\":\"" + safeDate(exp.getDate()) + "\""
                                            + ",\"consultantName\":\"" + consultantName + "\"}",
                                    Instant.now()
                            )
                    );
                }
            } catch (Exception e) {
                log.warn("Indexation expense Weaviate échouée: {}", e.getMessage());
            }
        }
        return valid;
    }

    private void assignId(ExpenseItem exp) {
        try {
            LocalDate d = resolveDate(exp.getDate());
            int id = safeNextId(idGenerator.nextId(d), d);
            exp.setId(id);
        } catch (Exception e) {
            log.warn("Impossible de générer l'id incrémental: {}", e.getMessage());
        }
    }

    private int safeNextId(int candidate, LocalDate d) {
        try {
            int max = expenseRepo.findMaxExpenseId(YearMonth.from(d));
            return candidate <= max ? max + 1 : candidate;
        } catch (Exception ignored) {
            return candidate;
        }
    }

    // ===========================================================
    // 2. Pipeline : générer un RAPPORT
    // ===========================================================
    public ReasoningResult extractExpenseReport(String text, IntentResult intent) {
        log.info("Pipeline: generate_expense_report");
        long start = System.currentTimeMillis();

        String rewritten = rewriteService.rewrite(text);
        var range = semanticSearch.extractDateRange(rewritten);
        String company = extractCompany(intent);

        ExpenseReportResponse report;
        try {
            report = expenseReportService.report(
                    range.start().toString(),
                    range.end().toString(),
                    null,
                    null,
                    company
            );
        } catch (IllegalStateException e) {
            log.error("company/organisation manquante pour le rapport", e);
            return ReasoningResult.error(e.getMessage());
        }
        String pdfPath = generatePdfToTemp(range.start().toString(), range.end().toString(), company);

        List<ExpenseItem> expenses = report.getExpenses();
        long duration = System.currentTimeMillis() - start;

        return ReasoningResult.builder()
                .type("generate_expense_report")
                .status("EXPENSE_REPORT")
                .confidence(intent.getConfidence())
                .raw(text)
                .expenses(expenses)
                .metadata(Map.of(
                        "rewrittenQuery", rewritten,
                        "foundExpenses", expenses == null ? 0 : expenses.size(),
                        "start", report.getStart(),
                        "end", report.getEnd(),
                        "totalsByCurrency", report.getTotalsByCurrency(),
                        "totalsByType", report.getTotalsByType(),
                        PROCESSING_MS, duration,
                        "pdfPath", pdfPath != null ? pdfPath : ""
                ))
                .build();
    }

    // ===========================================================
    // PROMPTS
    // ===========================================================

    private String buildSingleExpenseUserPrompt(String original, List<String> context) {
        String ctx = context == null || context.isEmpty() ? "n/a" : String.join("\n---\n", context);
        return "TEXTE:\n" + (original == null ? "" : original)
                + "\n\nCONTEXTE:\n" + ctx;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String safeType(String t) { return t == null ? "" : t; }
    private String safeDate(String d) { return d == null ? "?" : d; }

    private boolean containsAny(String value, String... needles) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (normalized.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private void enrichFromText(ExpenseItem e, String raw) {
        if (e == null) return;
        if (isBlank(e.getOriginalText())) {
            e.setOriginalText(raw);
        }
    }

    private void normalizeExpense(ExpenseItem e, String raw) {
        if (e == null) {
            return;
        }

        String type = e.getType();
        String description = e.getDescription();
        boolean locationExpense = containsAny(type, "location", "domiciliation", "rent", "loyer")
                || containsAny(description, "domiciliation", "location", "rent", "loyer")
                || containsAny(raw, "domiciliation", "location", "rent", "loyer", "je loue");

        if (locationExpense) {
            e.setType("location");
            e.setPaymentMode(PAYMENT_MODE_PERSONNEL);
            if (isBlank(e.getDescription())) {
                e.setDescription("Location / domiciliation");
            }
        }
    }

    private void enrichKm(ExpenseItem e, String raw) {
        if (e == null) return;
        if (!"frais_km".equalsIgnoreCase(e.getType() == null ? "" : e.getType())) {
            return;
        }
        if (e.getKm() != null) {
            if (e.getAmount() == null) {
                double costPerKm = computeCostPerKm(kmAnnual, kmRate);
                e.setAmount(round2(costPerKm * e.getKm()));
            }
            if (isBlank(e.getCurrency())) {
                e.setCurrency("EUR");
            }
        }
        if (isBlank(e.getDescription())) {
            e.setDescription("Frais km automatiques" + (e.getKm() != null ? " (" + e.getKm().intValue() + " km)" : ""));
        }
        if (isBlank(e.getPaymentMode())) {
            e.setPaymentMode(PAYMENT_MODE_PERSONNEL);
        }
        if (isBlank(e.getAddress())) {
            e.setAddress("lieu de déplacement");
        }
    }

    private boolean isKmMonthly(ExpenseItem e, String raw) {
        if (e == null) return false;
        if (!"frais_km".equalsIgnoreCase(e.getType())) return false;
        return Boolean.TRUE.equals(e.getMonthly());
    }

    private List<ExpenseItem> expandKmMonthly(ExpenseItem base, String rawText) {
        List<ExpenseItem> list = new ArrayList<>();
        YearMonth ym = YearMonth.from(resolveDate(base.getDate()));
        Set<MonthDay> holidays = frenchFixedHolidays();
        Set<LocalDate> absentDates = resolveAbsentDates(base.getAbsencePeriods());

        for (int day = 1; day <= ym.lengthOfMonth(); day++) {
            LocalDate d = ym.atDay(day);
            if (isWorkingDay(d, holidays) && !absentDates.contains(d)) {
                ExpenseItem clone = copyExpense(base);
                clone.setDate(d.toString());
                list.add(clone);
            }
        }
        return list;
    }

    private Set<LocalDate> resolveAbsentDates(List<ExpenseItem.AbsencePeriod> periods) {
        Set<LocalDate> absent = new HashSet<>();
        if (periods == null || periods.isEmpty()) return absent;
        for (ExpenseItem.AbsencePeriod p : periods) {
            if (p.getFrom() == null || p.getTo() == null) continue;
            try {
                LocalDate from = LocalDate.parse(p.getFrom());
                LocalDate to   = LocalDate.parse(p.getTo());
                LocalDate d = from;
                while (!d.isAfter(to)) {
                    absent.add(d);
                    d = d.plusDays(1);
                }
            } catch (Exception e) {
                log.warn("Période d'absence ignorée (format invalide) : from={} to={} — {}", p.getFrom(), p.getTo(), e.getMessage());
            }
        }
        return absent;
    }

    private ExpenseItem copyExpense(ExpenseItem src) {
        ExpenseItem e = new ExpenseItem();
        e.setId(src.getId());
        e.setAmount(src.getAmount());
        e.setCurrency(src.getCurrency());
        e.setStatus(src.getStatus());
        e.setType(src.getType());
        e.setKm(src.getKm());
        e.setDate(src.getDate());
        e.setDescription(src.getDescription());
        e.setOriginalText(src.getOriginalText());
        e.setPaymentMode(src.getPaymentMode());
        e.setAddress(src.getAddress());
        e.setCompany(src.getCompany());
        e.setConsultantEmail(src.getConsultantEmail());
        e.setAbsencePeriods(src.getAbsencePeriods());
        return e;
    }

    private boolean isWorkingDay(LocalDate d, Set<MonthDay> holidays) {
        if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }
        return !holidays.contains(MonthDay.from(d));
    }

    private Set<MonthDay> frenchFixedHolidays() {
        Set<MonthDay> h = new HashSet<>();
        h.add(MonthDay.of(1, 1));
        h.add(MonthDay.of(5, 1));
        h.add(MonthDay.of(5, 8));
        h.add(MonthDay.of(7, 14));
        h.add(MonthDay.of(8, 15));
        h.add(MonthDay.of(11, 1));
        h.add(MonthDay.of(11, 11));
        h.add(MonthDay.of(12, 25));
        return h;
    }

    private double computeCostPerKm(int kmAnnual, double kmRate) {
        if (kmAnnual <= 0) {
            return kmRate;
        }
        double d = kmAnnual;
        double annualCost;
        if (d <= 5000) {
            annualCost = d * 0.697;
        } else if (d <= 20000) {
            annualCost = (d * 0.394) + 1515;
        } else {
            annualCost = d * 0.470;
        }
        return annualCost / d;
    }

    private double round2(double value) {
        return java.math.BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private LocalDate resolveDate(String date) {
        if (date == null || date.isBlank()) {
            return dateProvider.todayUtc();
        }
        try {
            return LocalDate.parse(date);
        } catch (Exception ignored) {
        }
        try {
            return java.time.OffsetDateTime.parse(date).toLocalDate();
        } catch (Exception ignored) {
        }
        return dateProvider.todayUtc();
    }

    private String extractCompany(IntentResult intent) {
        if (intent != null && intent.getEntities() != null) {
            Object c = intent.getEntities().get("company");
            if (c instanceof String s && !s.isBlank()) {
                return s.trim();
            }
        }
        if (defaultCompanyName != null && !defaultCompanyName.isBlank()) {
            return defaultCompanyName;
        }
        throw new IllegalStateException("Le nom de société / organisation est requis pour générer un rapport.");
    }

    private String generatePdfToTemp(String start, String end, String company) {
        try {
            String safeCompany = (company == null || company.isBlank()) ? "no-company" : company.replaceAll("[^a-zA-Z0-9_-]", "-");
            String label;
            try {
                YearMonth ym = YearMonth.parse(start.substring(0, 7));
                boolean sameMonth = ym.equals(YearMonth.parse(end.substring(0, 7)));
                if (sameMonth) {
                    label = ym.getMonth().getDisplayName(TextStyle.FULL, Locale.FRENCH).toLowerCase(Locale.ROOT);
                } else {
                    label = start + "_to_" + end;
                }
            } catch (Exception ignored) {
                label = start + "_to_" + end;
            }
            String filename = "/tmp/rapport-" + label + "-" + safeCompany + ".pdf";
            byte[] pdfBytes = expensePdfService.buildPdf(start, end, null, null, company);
            Files.write(Path.of(filename), pdfBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Rapport PDF généré: {}", filename);
            return filename;
        } catch (Exception e) {
            log.warn("Impossible de générer le PDF vers /tmp: {}", e.getMessage());
            return null;
        }
    }
}
