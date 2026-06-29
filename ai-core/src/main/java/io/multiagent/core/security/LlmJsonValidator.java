package io.multiagent.core.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

/**
 * Validation de schéma JSON sur les réponses LLM (OWASP LLM02).
 *
 * <p>Le LLM peut retourner des structures inattendues, des champs manquants,
 * ou des valeurs invalides. Cette classe valide que la sortie respecte
 * le contrat attendu avant traitement métier.
 *
 * <p>Utilisation :
 * <pre>
 *   LlmJsonValidator.ValidationResult r = LlmJsonValidator.validateExpense(json);
 *   if (!r.valid()) log.warn("LLM output invalide : {}", r.reason());
 * </pre>
 */
@Slf4j
public final class LlmJsonValidator {

    private LlmJsonValidator() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Types de dépenses valides côté serveur. */
    private static final Set<String> VALID_EXPENSE_TYPES = Set.of(
        "restaurant", "boulangerie", "café", "cafe", "hôtel", "hotel",
        "taxi", "transport", "carburant", "péage", "peage",
        "matériel informatique", "materiel informatique", "pc portable",
        "location", "frais_km", "carte_transport", "abonnement", "autre"
    );

    /** Résultat immuable d'une validation. */
    public record ValidationResult(boolean valid, String reason) {
        public static ValidationResult ok()              { return new ValidationResult(true, null); }
        public static ValidationResult fail(String msg)  { return new ValidationResult(false, msg); }
    }

    /**
     * Valide le JSON d'une note de frais retourné par le LLM.
     * Ne bloque pas si le champ est absent (le LLM peut mettre null légitimement),
     * mais signale les incohérences de type ou de valeur.
     */
    public static ValidationResult validateExpense(String json) {
        if (json == null || json.isBlank()) {
            return ValidationResult.fail("JSON vide");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (Exception e) {
            return ValidationResult.fail("JSON malformé : " + e.getMessage());
        }

        if (!node.isObject()) {
            return ValidationResult.fail("Réponse LLM n'est pas un objet JSON (reçu : " + node.getNodeType() + ")");
        }

        // Valider le type si présent
        JsonNode typeNode = node.get("type");
        if (typeNode != null && !typeNode.isNull()) {
            String type = typeNode.asText("").toLowerCase().trim();
            if (!type.isBlank() && !VALID_EXPENSE_TYPES.contains(type)) {
                log.warn("🛡️ [JSON-SCHEMA] Type de dépense inconnu : '{}' — traité comme 'autre'", type);
                // Non bloquant : on normalise vers "autre"
            }
        }

        // Valider amount si présent
        JsonNode amountNode = node.get("amount");
        if (amountNode != null && !amountNode.isNull()) {
            if (!amountNode.isNumber()) {
                return ValidationResult.fail("Champ 'amount' n'est pas un nombre : " + amountNode);
            }
            if (amountNode.asDouble() < 0) {
                return ValidationResult.fail("Champ 'amount' négatif : " + amountNode.asDouble());
            }
        }

        // Valider date si présente
        JsonNode dateNode = node.get("date");
        if (dateNode != null && !dateNode.isNull()) {
            String date = dateNode.asText("").trim();
            if (!date.isBlank() && !date.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                log.warn("🛡️ [JSON-SCHEMA] Format date invalide : '{}' (attendu YYYY-MM-DD)", date);
                // Non bloquant : sera géré par resolveDate()
            }
        }

        // Valider km si présent (doit être un nombre positif)
        JsonNode kmNode = node.get("km");
        if (kmNode != null && !kmNode.isNull()) {
            if (!kmNode.isNumber()) {
                return ValidationResult.fail("Champ 'km' n'est pas un nombre : " + kmNode);
            }
            if (kmNode.asDouble() < 0) {
                return ValidationResult.fail("Champ 'km' négatif : " + kmNode.asDouble());
            }
            if (kmNode.asDouble() > 2000) {
                log.warn("🛡️ [JSON-SCHEMA] Kilométrage suspect : {} km/jour", kmNode.asDouble());
            }
        }

        return ValidationResult.ok();
    }

    /**
     * Valide le JSON d'un résultat de classification d'intent.
     */
    public static ValidationResult validateIntent(String json) {
        if (json == null || json.isBlank()) return ValidationResult.fail("JSON vide");
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!node.isObject())         return ValidationResult.fail("Pas un objet JSON");
            if (!node.has("intent"))      return ValidationResult.fail("Champ 'intent' manquant");
            if (!node.has("confidence"))  return ValidationResult.fail("Champ 'confidence' manquant");

            double confidence = node.path("confidence").asDouble(-1);
            if (confidence < 0 || confidence > 1) {
                return ValidationResult.fail("confidence hors bornes [0,1] : " + confidence);
            }
            return ValidationResult.ok();
        } catch (Exception e) {
            return ValidationResult.fail("JSON malformé : " + e.getMessage());
        }
    }
}
