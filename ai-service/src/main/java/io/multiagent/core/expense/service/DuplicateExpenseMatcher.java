package io.multiagent.core.expense.service;

import io.multiagent.core.expense.entity.ExpenseEntity;
import io.multiagent.core.model.ExpenseItem;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Détecte si une nouvelle dépense (texte libre ou justificatif) est un doublon d'une dépense déjà
 * persistée le même jour, pour le même consultant — même type, montant proche, description similaire.
 * Une dépense REFUSED ne bloque jamais une resoumission (le consultant a le droit de corriger et renvoyer).
 */
public final class DuplicateExpenseMatcher {

    private DuplicateExpenseMatcher() {}

    private static final double AMOUNT_TOLERANCE = 0.01; // 1% — absorbe les arrondis

    /** Retourne {@code true} si {@code candidate} est un doublon probable de {@code existing}. */
    public static boolean isDuplicate(ExpenseEntity existing, ExpenseItem candidate) {
        if (existing == null || candidate == null) return false;
        if ("REFUSED".equalsIgnoreCase(existing.getApprovalStatus())) return false;

        if (!sameType(existing.getType(), candidate.getType())) return false;
        if (!sameAmount(existing.getAmount(), candidate.getAmount())) return false;
        return similarDescription(existing.getDescription(), candidate.getDescription());
    }

    private static boolean sameType(String a, String b) {
        if (isBlank(a) || isBlank(b)) return false; // type manquant → pas de comparaison fiable possible
        return normalize(a).equals(normalize(b));
    }

    private static boolean sameAmount(java.math.BigDecimal existingAmount, Double candidateAmount) {
        if (existingAmount == null || candidateAmount == null) return false;
        double existing = existingAmount.doubleValue();
        if (existing <= 0) return false;
        double diff = Math.abs(existing - candidateAmount) / existing;
        return diff <= AMOUNT_TOLERANCE;
    }

    /** Similarité tolérante (accents/casse ignorés, égalité ou sous-chaîne). Pas de description des deux côtés → considéré comme non concluant (pas de blocage). */
    private static boolean similarDescription(String a, String b) {
        if (isBlank(a) || isBlank(b)) return false;
        String na = normalize(a);
        String nb = normalize(b);
        return na.equals(nb) || na.contains(nb) || nb.contains(na);
    }

    private static String normalize(String s) {
        String noAccents = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noAccents.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
