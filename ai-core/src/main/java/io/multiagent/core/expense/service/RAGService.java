package io.multiagent.core.expense.service;

import io.multiagent.core.client.LLMAIClient;
import io.multiagent.core.infrastructure.kafka.DomainEvent;
import io.multiagent.core.infrastructure.kafka.EventPublisher;
import io.multiagent.core.infrastructure.kafka.KafkaTopics;
import io.multiagent.core.model.ExpenseItem;
import io.multiagent.core.model.IntentResult;
import io.multiagent.core.model.ReasoningResult;
import io.multiagent.core.model.ExpenseReportResponse;
import io.multiagent.core.util.DateProvider;
import io.multiagent.core.reasoning.service.QueryRewriteService;
import io.multiagent.core.reasoning.service.SemanticSearchService;
import io.multiagent.core.reasoning.service.ReRankService;
import io.multiagent.core.notification.service.NotificationService;
import io.multiagent.core.service.WeaviateService;import jakarta.annotation.PostConstruct;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Locale;

/**
 * Orchestrates the full RAG flow (rewrite → search → rerank → reasoning LLM).
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
    private final WeaviateService weaviateService;
    private final ExpenseIdGenerator idGenerator;
    private final ExpensePdfService expensePdfService;
    private final NotificationService notificationService;
    private final io.multiagent.core.settings.repository.ConsultantProfileJpaRepository consultantProfileRepo;
    private final io.multiagent.core.expense.repository.ExpenseJpaRepository expenseRepo;

    // Kafka best-effort : null si le broker n'est pas disponible en dev local
    @Autowired(required = false)
    private EventPublisher eventPublisher;
    @Value("${ai-core.expense.km-rate-7cv:0.661}")
    private double kmRate;
    @Value("${ai-core.expense.km-annual:4999}")
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
    // 1️⃣ Pipeline : créer UNE note de frais
    // ===========================================================
    public ReasoningResult extractSingleExpense(String text, IntentResult intent) {
        return extractSingleExpense(text, intent, null);
    }

    public ReasoningResult extractSingleExpense(String text, IntentResult intent, String consultantEmail) {
        log.info("🔵 Pipeline: create_expense");
        long start = System.currentTimeMillis();

        String rewritten = rewriteService.rewrite(text);
        List<String> docs = semanticSearch.searchTopK(rewritten, 5);
        List<String> sortedDocs = rerankService.rerankAndExtractTopK(rewritten, docs, 3);

        String json = llm.extractJSON(
                singleExpensePromptTemplate.formatted(dateProvider.todayUtc().toString()),
                buildSingleExpenseUserPrompt(text, sortedDocs));
        List<ExpenseItem> items = ExpenseItem.fromJsonArray("[" + json + "]");
        if (items.isEmpty()) {
            log.error("❌ Impossible d'extraire une dépense structurée du JSON retourné par le LLM pour le texte : {}", text);
            return ReasoningResult.error("Le LLM n'a pas pu extraire de dépense valide à partir du texte fourni.");
        }

        ExpenseItem expense = items.get(0);
        ReasoningResult validationError = prepareExpense(expense, text, consultantEmail);
        if (validationError != null) return validationError;

        boolean monthly = isKmMonthly(expense, text);
        List<ExpenseItem> expanded = monthly ? expandKmMonthly(expense, text) : List.of(expense);
        List<ExpenseItem> validExpenses = indexExpenses(expanded);
        pushExpenseNotification(validExpenses, expense, consultantEmail, monthly);

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
        ReasoningResult kmError = enrichKm(expense, text, consultantEmail);
        if (kmError != null) return kmError;
        if ("carte_transport".equalsIgnoreCase(expense.getType())) {
            applyTransportCardReimbursement(expense);
        }
        ReasoningResult exclusivityError = checkMonthlyExclusivity(consultantEmail, expense);
        if (exclusivityError != null) return exclusivityError;
        if (isBlank(expense.getPaymentMode())) expense.setPaymentMode(PAYMENT_MODE_PERSONNEL);
        if (!isBlank(consultantEmail)) {
            if (!consultantEmail.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                log.warn("⚠️ consultantEmail invalide ignoré : format non conforme");
            } else {
                expense.setConsultantEmail(consultantEmail.toLowerCase(java.util.Locale.ROOT));
            }
        }
        if (isBlank(expense.getCompany()) && !isBlank(defaultCompanyName)) expense.setCompany(defaultCompanyName);
        if (isBlank(expense.getCompany())) {
            String msg = "Société/organisation manquante : indiquez le nom ou configurez AI_CORE_COMPANY_NAME";
            log.error("❌ {}", msg);
            return ReasoningResult.error(msg);
        }
        if (isBlank(expense.getCurrency())) expense.setCurrency("EUR");
        if (isBlank(expense.getAddress())) expense.setAddress("Paris, France");
        return null;
    }

    /** Génère les ids, valide et indexe chaque expense dans Weaviate. */
    private List<ExpenseItem> indexExpenses(List<ExpenseItem> expenses) {
        List<ExpenseItem> valid = new java.util.ArrayList<>();
        for (ExpenseItem exp : expenses) {
            assignId(exp);
            if (exp.getAmount() == null || isBlank(exp.getCurrency())
                    || isBlank(exp.getType()) || isBlank(exp.getPaymentMode()) || isBlank(exp.getAddress())) {
                log.warn("⚠️ Expense invalide (champ obligatoire manquant) après extraction: {}", exp);
                continue;
            }
            try {
                weaviateService.indexExpense(null, exp, "text", false, null);
                valid.add(exp);
                // frais_km : notification groupée envoyée par extractSingleExpense — on skip ici
                if (!"frais_km".equalsIgnoreCase(exp.getType())) {
                    String email = exp.getConsultantEmail() != null ? exp.getConsultantEmail() : "inconnu";
                    notificationService.push(
                            "EXPENSE_CREATED",
                            email,
                            exp.getConsultantEmail(),
                            email + " — " + safeType(exp.getType()) + " " + exp.getAmount() + " EUR le " + safeDate(exp.getDate()),
                            String.valueOf(exp.getId())
                    );
                }
                if (eventPublisher != null) {
                    eventPublisher.publish(
                            KafkaTopics.EXPENSE_CREATED,
                            new DomainEvent(
                                    "EXPENSE_CREATED",
                                    String.valueOf(exp.getId()),
                                    exp.getConsultantEmail(),
                                    exp.getCompany(),
                                    "{\"type\":\"" + safeType(exp.getType()) + "\",\"amount\":" + exp.getAmount() + ",\"date\":\"" + safeDate(exp.getDate()) + "\"}",
                                    Instant.now()
                            )
                    );
                }
            } catch (Exception e) {
                log.warn("⚠️ Indexation expense Weaviate échouée: {}", e.getMessage());
            }
        }
        return valid;
    }

    /**
     * Pousse UNE notification de synthèse pour les frais km (groupée) ou une notification
     * standard enrichie de l'email pour les autres types (déjà poussées dans indexExpenses).
     */
    private void pushExpenseNotification(List<ExpenseItem> valid, ExpenseItem base,
                                         String consultantEmail, boolean monthly) {
        if (valid.isEmpty()) return;
        if (!"frais_km".equalsIgnoreCase(base.getType())) return; // non-km déjà notifiés
        String email = consultantEmail != null ? consultantEmail : "inconnu";
        String refId = String.valueOf(valid.get(0).getId());
        String message;
        if (monthly) {
            YearMonth ym = YearMonth.from(resolveDate(base.getDate()));
            String mois = ym.getMonth().getDisplayName(TextStyle.FULL, Locale.FRENCH) + " " + ym.getYear();
            int km = base.getKm() != null ? base.getKm().intValue() : 0;
            message = email + " — frais km mensuel " + mois + " : " + valid.size() + " jours × " + km + " km";
        } else {
            message = email + " — frais_km " + base.getAmount() + " EUR le " + safeDate(base.getDate());
        }
        notificationService.push("EXPENSE_CREATED", email, consultantEmail, message, refId);
    }

    private void assignId(ExpenseItem exp) {
        try {
            LocalDate d = resolveDate(exp.getDate());
            int id = safeNextId(idGenerator.nextId(d), d);
            exp.setId(id);
        } catch (Exception e) {
            log.warn("⚠️ Impossible de générer l'id incrémental: {}", e.getMessage());
        }
    }

    private int safeNextId(int candidate, LocalDate d) {
        try {
            int max = weaviateService.findMaxExpenseId(java.time.YearMonth.from(d));
            return candidate <= max ? max + 1 : candidate;
        } catch (Exception ignored) {
            return candidate;
        }
    }

    // ===========================================================
    // 2️⃣ Pipeline : générer un RAPPORT
    // ===========================================================
    public ReasoningResult extractExpenseReport(String text, IntentResult intent) {

        log.info("🟢 Pipeline: generate_expense_report");
        long start = System.currentTimeMillis();
        // 1. Réécriture
        String rewritten = rewriteService.rewrite(text);

        // 2. Extraction de période (structurée)
        var range = semanticSearch.extractDateRange(rewritten);

        String company = extractCompany(intent);
        // 3. Requête structurée Weaviate
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
            log.error("❌ company/organisation manquante pour le rapport", e);
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
                        "pdfPath", pdfPath
                ))
                .build();
       // return ReasoningResult.expenseReport(expenses, intent.getConfidence());
    }

    // ===========================================================
    // 3️⃣ Pipeline : extraire les données d'une FACTURE
    // ===========================================================
    public ReasoningResult extractInvoiceData(String text, IntentResult intent) {
        log.info("🔵 Pipeline: generate_invoice");
        long start = System.currentTimeMillis();

        // Pas de RAG pour la facture, juste une extraction structurée.
        // Le prompt est conçu pour prendre le texte brut et retourner du JSON.
        String systemPrompt = invoicePromptTemplate;
        String userPrompt = text;

        log.info("invoice-extraction system prompt:\n{}", systemPrompt);

        String json = llm.extractJSON(systemPrompt, userPrompt);
        log.info("extractJSON for invoice: {}", json);

        long duration = System.currentTimeMillis() - start;

        return ReasoningResult.builder()
                .type("generate_invoice")
                .status("INVOICE_DATA_EXTRACTED")
                .confidence(intent.getConfidence())
                .raw(text)
                // On met le JSON brut dans les métadonnées pour que le Reassign-Agent le récupère
                .metadata(Map.of(
                        "invoiceDataJson", json,
                        PROCESSING_MS, duration
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

    private String buildExpenseListPrompt(List<String> docs) {
        return expenseListPromptTemplate.formatted(String.join("\n---\n", docs));
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

    /**
     * Fallback minimal : on n’enrichit plus amount/currency/type/date/address,
     * on se contente de renseigner l’originalText si absent.
     * L’objectif est d’éviter d’indexer des valeurs erronées quand le LLM est incomplet.
     */
    private void enrichFromText(ExpenseItem e, String raw) {
        if (e == null) return;
        if (isBlank(e.getOriginalText())) {
            e.setOriginalText(raw);
        }
    }

    /**
     * Corrige certaines sorties LLM pour les aligner sur les règles métier.
     * La location/domiciliation reste une dépense personnelle remboursable.
     */
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

    private record VehicleProfile(io.multiagent.core.model.VehicleType vehicleType, int fiscalPower, int kmAnnual) {}

    private VehicleProfile resolveVehicleProfile(String consultantEmail) {
        if (isBlank(consultantEmail)) return new VehicleProfile(io.multiagent.core.model.VehicleType.CAR, 7, kmAnnual);
        return consultantProfileRepo
                .findByTenantIdAndEmailIgnoreCase(
                        io.multiagent.core.infrastructure.tenant.TenantContext.getTenantId(), consultantEmail)
                .map(p -> new VehicleProfile(
                        p.getVehicleType() != null ? p.getVehicleType() : io.multiagent.core.model.VehicleType.CAR,
                        p.getFiscalPower() != null ? p.getFiscalPower() : 7,
                        p.getKmAnnual() != null ? p.getKmAnnual() : kmAnnual))
                .orElse(new VehicleProfile(io.multiagent.core.model.VehicleType.CAR, 7, kmAnnual));
    }

    /**
     * Vérifie le véhicule et calcule le montant km selon le barème fiscal du profil consultant.
     * Retourne une erreur si le consultant n’a pas de véhicule.
     */
    private ReasoningResult enrichKm(ExpenseItem e, String raw, String consultantEmail) {
        if (e == null) return null;
        if (!"frais_km".equalsIgnoreCase(e.getType() == null ? "" : e.getType())) {
            return null;
        }

        VehicleProfile vp = resolveVehicleProfile(consultantEmail);

        if (vp.vehicleType() == io.multiagent.core.model.VehicleType.NONE) {
            log.warn("⚠️ Frais km refusés : consultant {} n’a pas de véhicule enregistré", consultantEmail);
            return ReasoningResult.error(
                    "Votre profil n’indique aucun véhicule personnel. " +
                    "Les frais kilométriques ne peuvent pas être remboursés sans véhicule. " +
                    "Si vous utilisez les transports en commun, soumettez votre abonnement de transport " +
                    "en précisant le montant de votre carte ou pass mensuel.");
        }

        if (e.getKm() != null && e.getAmount() == null) {
            double costPerKm = computeCostPerKm(vp.kmAnnual(), vp.vehicleType());
            e.setAmount(round2(costPerKm * e.getKm()));
            log.info("📐 Barème km: vehicleType={} fiscalPower={}CV kmAnnual={} taux={}/km montant={}€",
                    vp.vehicleType(), vp.fiscalPower(), vp.kmAnnual(),
                    String.format("%.4f", costPerKm), e.getAmount());
        }
        if (isBlank(e.getCurrency())) e.setCurrency("EUR");
        if (isBlank(e.getDescription())) {
            e.setDescription("Frais km automatiques" + (e.getKm() != null ? " (" + e.getKm().intValue() + " km)" : ""));
        }
        if (isBlank(e.getPaymentMode())) e.setPaymentMode(PAYMENT_MODE_PERSONNEL);
        if (isBlank(e.getAddress())) e.setAddress("lieu de déplacement");
        return null;
    }

    /** Applique le remboursement à 50% du montant de la carte de transport. */
    private void applyTransportCardReimbursement(ExpenseItem e) {
        if (e.getAmount() != null) {
            double fullAmount = e.getAmount();
            e.setAmount(round2(fullAmount * 0.50));
            String base = isBlank(e.getDescription()) ? "Carte de transport" : e.getDescription();
            e.setDescription(base + String.format(" — remboursement 50%% (abonnement %.2f€)", fullAmount));
        }
        if (isBlank(e.getCurrency())) e.setCurrency("EUR");
        e.setPaymentMode(PAYMENT_MODE_PERSONNEL);
    }

    /** Vérifie qu’un consultant ne cumule pas frais_km et carte_transport sur le même mois. */
    private ReasoningResult checkMonthlyExclusivity(String consultantEmail, ExpenseItem e) {
        if (isBlank(consultantEmail)) return null;
        String type = e.getType();
        if (!"frais_km".equalsIgnoreCase(type) && !"carte_transport".equalsIgnoreCase(type)) return null;

        LocalDate expenseDate = resolveDate(e.getDate());
        LocalDate start = expenseDate.withDayOfMonth(1);
        LocalDate end   = expenseDate.withDayOfMonth(expenseDate.lengthOfMonth());
        java.util.UUID tenantId = io.multiagent.core.infrastructure.tenant.TenantContext.getTenantId();

        String conflictType = "frais_km".equalsIgnoreCase(type) ? "carte_transport" : "frais_km";
        boolean hasConflict = expenseRepo.existsByMonthAndType(tenantId, consultantEmail, conflictType, start, end);

        if (hasConflict) {
            if ("frais_km".equalsIgnoreCase(type)) {
                return ReasoningResult.error(
                        "Vous avez déjà enregistré un remboursement de carte de transport ce mois-ci (" +
                        expenseDate.getMonth().getDisplayName(TextStyle.FULL, Locale.FRENCH) + " " + expenseDate.getYear() + "). " +
                        "Il n’est pas possible de cumuler frais kilométriques et carte de transport sur le même mois.");
            } else {
                return ReasoningResult.error(
                        "Vous avez déjà des frais kilométriques enregistrés ce mois-ci (" +
                        expenseDate.getMonth().getDisplayName(TextStyle.FULL, Locale.FRENCH) + " " + expenseDate.getYear() + "). " +
                        "Il n’est pas possible de cumuler frais kilométriques et carte de transport sur le même mois.");
            }
        }
        return null;
    }

    private boolean isKmMonthly(ExpenseItem e, String raw) {
        if (e == null) return false;
        if (!"frais_km".equalsIgnoreCase(e.getType())) return false;
        // Le LLM signale explicitement via le champ "monthly" si la dépense couvre un mois entier.
        // Pas de regex ni de noms de mois codés en dur : le prompt gère la détection multi-langue.
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

    private Set<LocalDate> resolveAbsentDates(List<io.multiagent.core.model.ExpenseItem.AbsencePeriod> periods) {
        Set<LocalDate> absent = new HashSet<>();
        if (periods == null || periods.isEmpty()) return absent;
        for (io.multiagent.core.model.ExpenseItem.AbsencePeriod p : periods) {
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
                log.warn("⚠️ Période d'absence ignorée (format invalide) : from={} to={} — {}", p.getFrom(), p.getTo(), e.getMessage());
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

    /** Calcule le taux €/km selon le type de véhicule (barème fiscal 2025). */
    private double computeCostPerKm(int kmAnnual, io.multiagent.core.model.VehicleType vehicleType) {
        if (vehicleType == io.multiagent.core.model.VehicleType.MOTORCYCLE) {
            return computeMotoCostPerKm(kmAnnual);
        }
        double rate = computeVoitureCostPerKm(kmAnnual);
        if (vehicleType == io.multiagent.core.model.VehicleType.ELECTRIC_CAR) rate *= 1.20;
        return rate;
    }

    /** Barème voiture 7CV+ (2025) : ≤5 000 km, 5 001-20 000 km, >20 000 km. */
    private double computeVoitureCostPerKm(int kmAnnual) {
        if (kmAnnual <= 0) return kmRate;
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

    /** Barème moto >500cc (2025) : ≤3 000 km, 3 001-6 000 km, >6 000 km. */
    private double computeMotoCostPerKm(int kmAnnual) {
        if (kmAnnual <= 0) return 0.412;
        double d = kmAnnual;
        double annualCost;
        if (d <= 3000) {
            annualCost = d * 0.412;
        } else if (d <= 6000) {
            annualCost = (d * 0.274) + 412;
        } else {
            annualCost = d * 0.274;
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

    // Suppression de l'expansion mensuelle côté code : la gestion des mentions de mois est déléguée au prompt.

    /**
     * Génère un PDF du rapport et le sauvegarde dans /tmp, renvoie le chemin.
     */
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
            log.info("📄 Rapport PDF généré: {}", filename);
            return filename;
        } catch (Exception e) {
            log.warn("⚠️ Impossible de générer le PDF vers /tmp: {}", e.getMessage());
            return null;
        }
    }

}
