package io.multiagent.core.policy.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.multiagent.core.client.LLMAIClient;
import io.multiagent.core.model.ExpenseItem;
import io.multiagent.core.policy.entity.PolicyDocumentEntity;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Évalue (si une politique ACTIVE existe pour le tenant courant) la conformité d'une dépense à sa
 * politique de remboursement, et retourne un verdict à titre CONSULTATIF — n'influence jamais
 * approvalStatus et ne bloque jamais la création. Utilisé par les deux pipelines de création de
 * dépense (texte libre via {@code RAGService} et upload de justificatif via
 * {@code ReceiptUploadService}) — extrait de {@code RAGService} pour éviter que ce second pipeline
 * dépende de toute sa machinerie pour cette seule méthode.
 *
 * <p>Le texte complet de la politique active est injecté dans le prompt (pas de retrieval par
 * similarité) : une politique tient toujours en quelques pages, donc un retrieval top-k n'apporterait
 * rien et risquerait de manquer la clause pertinente pour aucun bénéfice.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyComplianceService {

    private static final String POLICY_TYPE_EXPENSE_REIMBURSEMENT = "EXPENSE_REIMBURSEMENT";

    private final LLMAIClient llm;
    private final PolicyIngestionService policyIngestionService;
    private final ObjectMapper objectMapper;
    private final io.multiagent.core.i18n.Messages messages;

    // Même modèle que l'extraction JSON des dépenses (RAGService) — cohérence de coût/latence.
    @Value("${OPENAI_EXPENSE_MODEL:openai/gpt-oss-120b}")
    private String expenseExtractionModel;

    // Optionnel : la fonctionnalité se désactive silencieusement si absent, car elle ne s'active
    // de toute façon que si le tenant a une politique ACTIVE.
    @Value("${AI_CORE_PROMPT_POLICY_COMPLIANCE:}")
    private String policyCompliancePromptEnv;
    private String policyCompliancePromptTemplate;

    // Optionnel : sans ce prompt, aucun plafond km journalier n'est appliqué (montants inchangés).
    @Value("${AI_CORE_PROMPT_POLICY_KM_DAILY_CAP:}")
    private String kmDailyCapPromptTemplate;

    // Messages utilisateur envoyés au LLM — placeholders {policy}, {type}, {amount}, {currency}, {description}
    @Value("${AI_CORE_PROMPT_POLICY_COMPLIANCE_USER:}")
    private String policyComplianceUserTemplate;
    @Value("${AI_CORE_PROMPT_POLICY_KM_DAILY_CAP_USER:}")
    private String kmDailyCapUserTemplate;

    // Clé = id de la version de politique : une version est immuable (toute modification crée une
    // nouvelle version, donc un nouvel id), le plafond n'est donc extrait qu'une fois par version.
    private final Map<UUID, Optional<KmDailyCap>> kmDailyCapCache = new ConcurrentHashMap<>();

    /** Plafond journalier des frais km extrait de la politique ACTIVE (ex. « 29 euros par jour »). */
    public record KmDailyCap(double capEur, String citedClause) {}

    @PostConstruct
    void loadPrompt() {
        policyCompliancePromptTemplate = policyCompliancePromptEnv;
        if (policyCompliancePromptTemplate == null || policyCompliancePromptTemplate.isBlank()) {
            log.info("AI_CORE_PROMPT_POLICY_COMPLIANCE absent — évaluation de conformité politique désactivée");
        }
    }

    /**
     * Évalue la dépense et retourne le verdict JSON ({@code {compliant, citedClause, explanation}})
     * sous forme de chaîne, ou {@code null} si aucune évaluation n'a pu être produite (pas de
     * politique active, prompt non configuré, ou erreur — toujours best-effort, jamais d'exception).
     */
    public String evaluate(ExpenseItem expense) {
        if (policyCompliancePromptTemplate == null || policyCompliancePromptTemplate.isBlank()) return null;
        try {
            var activePolicy = policyIngestionService.findActivePolicy(POLICY_TYPE_EXPENSE_REIMBURSEMENT);
            if (activePolicy.isEmpty()) return null;

            String json = llm.extractJSONWithModel(
                    expenseExtractionModel,
                    policyCompliancePromptTemplate,
                    buildUserPrompt(expense, activePolicy.get().getRawText()));
            Map<String, Object> verdict = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            return objectMapper.writeValueAsString(verdict);
        } catch (Exception e) {
            log.warn("⚠️ [POLICY] Évaluation de conformité ignorée (non bloquant) : {}", e.getMessage());
            return null;
        }
    }

    /**
     * Plafond journalier des frais km de la politique ACTIVE du tenant, ou vide si aucune politique,
     * aucune règle km, prompt non configuré ou erreur — best-effort, jamais d'exception. Un échec
     * LLM n'est pas mis en cache (nouvel essai à la prochaine déclaration).
     */
    public Optional<KmDailyCap> findKmDailyCap() {
        if (kmDailyCapPromptTemplate == null || kmDailyCapPromptTemplate.isBlank()) return Optional.empty();
        try {
            Optional<PolicyDocumentEntity> activePolicy =
                    policyIngestionService.findActivePolicy(POLICY_TYPE_EXPENSE_REIMBURSEMENT);
            if (activePolicy.isEmpty()) return Optional.empty();
            PolicyDocumentEntity policy = activePolicy.get();
            Optional<KmDailyCap> cached = kmDailyCapCache.get(policy.getId());
            if (cached != null) return cached;

            String json = llm.extractJSONWithModel(
                    expenseExtractionModel,
                    kmDailyCapPromptTemplate,
                    kmDailyCapUserTemplate.replace("{policy}", policy.getRawText()));
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            Optional<KmDailyCap> cap = Optional.empty();
            if (parsed.get("kmDailyCapEur") instanceof Number n
                    && Double.isFinite(n.doubleValue()) && n.doubleValue() > 0) {
                Object clause = parsed.get("citedClause");
                cap = Optional.of(new KmDailyCap(n.doubleValue(), clause instanceof String s ? s : null));
            }
            if (policy.getId() != null) kmDailyCapCache.put(policy.getId(), cap);
            log.info("🗂️ [POLICY] Plafond km journalier : {}", cap.map(c -> c.capEur() + " €").orElse("aucun"));
            return cap;
        } catch (Exception e) {
            log.warn("⚠️ [POLICY] Plafond km journalier ignoré (non bloquant) : {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Verdict ({@code compliant=false}) d'une ligne km dont le montant a été ramené au plafond journalier. */
    public String kmCapVerdictJson(KmDailyCap cap, double computedEur, double alreadyDeclaredEur, double grantedEur) {
        try {
            Map<String, Object> verdict = new LinkedHashMap<>();
            verdict.put("compliant", false);
            // Montant déjà ramené au plafond : le front admin le distingue d'un dépassement non corrigé.
            verdict.put("capped", true);
            verdict.put("citedClause", cap.citedClause());
            Locale locale = messages.defaultLocale();
            verdict.put("explanation", messages.getDefault("policy.verdict.kmCapped",
                    String.format(locale, "%.2f", grantedEur), String.format(locale, "%.2f", cap.capEur()),
                    String.format(locale, "%.2f", computedEur), String.format(locale, "%.2f", alreadyDeclaredEur)));
            return objectMapper.writeValueAsString(verdict);
        } catch (Exception e) {
            log.warn("⚠️ [POLICY] Verdict de plafonnement km non généré : {}", e.getMessage());
            return null;
        }
    }

    private String buildUserPrompt(ExpenseItem expense, String policyText) {
        // L'ordre de remplacement place le texte saisi (description) en dernier : il ne peut pas injecter de placeholder.
        return policyComplianceUserTemplate
                .replace("{policy}", policyText)
                .replace("{type}", String.valueOf(expense.getType()))
                .replace("{amount}", String.valueOf(expense.getAmount()))
                .replace("{currency}", String.valueOf(expense.getCurrency()))
                .replace("{description}", expense.getDescription() != null ? expense.getDescription() : "");
    }
}
