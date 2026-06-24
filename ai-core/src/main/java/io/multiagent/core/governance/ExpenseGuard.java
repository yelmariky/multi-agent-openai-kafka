package io.multiagent.core.governance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Guardrails métier appliqués aux extractions LLM des notes de frais.
 * Détermine si une dépense peut être traitée automatiquement ou requiert une revue humaine.
 */
@Slf4j
@Component
public class ExpenseGuard {

    private static final BigDecimal THRESHOLD_HIGH_AMOUNT = new BigDecimal("500.00");
    private static final BigDecimal THRESHOLD_KM          = new BigDecimal("500.0");

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "REPAS", "TRANSPORT", "HEBERGEMENT", "MATERIEL",
            "TELEPHONE", "FORMATION", "KM", "ABSENCE"
    );

    public record GuardResult(
            ReviewDecision decision,
            List<String> flags,
            double confidenceScore
    ) {
        public boolean requiresHumanReview() {
            return decision == ReviewDecision.REQUIRES_HUMAN_REVIEW;
        }

        /** Représentation JSON simple des flags pour stockage en DB. */
        public String flagsAsJson() {
            if (flags.isEmpty()) return "{}";
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < flags.size(); i++) {
                sb.append("\"").append(flags.get(i)).append("\":true");
                if (i < flags.size() - 1) sb.append(",");
            }
            sb.append("}");
            return sb.toString();
        }
    }

    public enum ReviewDecision {
        AUTO_APPROVABLE,
        REQUIRES_HUMAN_REVIEW
    }

    /**
     * Évalue une extraction LLM d'une note de frais.
     *
     * @param amount       montant extrait (peut être null si non détecté)
     * @param type         type de dépense extrait
     * @param expenseDate  date de la dépense
     * @param km           kilomètres (si transport KM)
     * @param confidenceScore score de confiance brut retourné par le LLM (0-1)
     */
    public GuardResult evaluate(BigDecimal amount, String type, LocalDate expenseDate,
                                BigDecimal km, double confidenceScore) {

        List<String> flags = new ArrayList<>();

        // 1. Montant élevé
        if (amount != null && amount.compareTo(THRESHOLD_HIGH_AMOUNT) > 0) {
            flags.add("MONTANT_ELEVE");
            log.info("ExpenseGuard: MONTANT_ELEVE détecté ({})", amount);
        }

        // 2. Date dans le futur
        if (expenseDate != null && expenseDate.isAfter(LocalDate.now())) {
            flags.add("DATE_FUTURE");
            log.warn("ExpenseGuard: DATE_FUTURE détectée ({})", expenseDate);
        }

        // 3. Type inconnu
        if (type != null && !ALLOWED_TYPES.contains(type.toUpperCase())) {
            flags.add("TYPE_INCONNU");
            log.warn("ExpenseGuard: TYPE_INCONNU ({})", type);
        }

        // 4. KM suspicieux
        if (km != null && km.compareTo(THRESHOLD_KM) > 0) {
            flags.add("KM_ELEVE");
        }

        // 5. Confiance LLM faible → revue systématique
        if (confidenceScore < 0.70) {
            flags.add("CONFIANCE_FAIBLE");
        }

        // 6. Montant absent (extraction échouée)
        if (amount == null) {
            flags.add("MONTANT_ABSENT");
        }

        ReviewDecision decision = flags.isEmpty()
                ? ReviewDecision.AUTO_APPROVABLE
                : ReviewDecision.REQUIRES_HUMAN_REVIEW;

        return new GuardResult(decision, flags, confidenceScore);
    }
}
