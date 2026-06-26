package io.multiagent.core.reasoning.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.multiagent.core.model.ReasoningResult;
import io.multiagent.core.reasoning.service.ReasoningService;
import io.multiagent.core.security.ScopeGuardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/reasoning")
public class ReasoningController {

    private static final String FIELD_INTENT = "intent";

    /**
     * Seuls ces intents sont autorisés depuis /reasoning/analyze (endpoint consultant).
     * delete_expense, delete_invoice, generate_invoice = opérations admin uniquement.
     */
    private static final Set<String> CONSULTANT_ALLOWED_INTENTS = Set.of(
            "create_expense", "generate_expense_report", "smalltalk", "error", "unknown"
    );

    private final ReasoningService reasoningService;
    private final ScopeGuardService scopeGuardService;
    private final ObjectMapper objectMapper;

    @PostMapping("/analyze")
    public ReasoningResult analyze(
            @RequestBody String payload,
            @RequestHeader(value = "X-Source", required = false) String source) {
        String normalizedPayload = normalizePayload(payload);
        String consultantEmail = extractField(payload, "consultantEmail");
        log.info("➡️ AI-Core /reasoning/analyze received payload ({} chars) normalized to {} chars, consultant={}",
                payload.length(), normalizedPayload.length(), maskEmail(consultantEmail));

        // ── Garde 1 (regex, instantanée) : mots-clés structurels sans appel LLM ─
        String suspectedIntent = detectDeleteIntent(normalizedPayload);
        if (suspectedIntent != null) {
            log.warn("🚫 [SCOPE REGEX] Opération bloquée sur /analyze (pattern={}), consultant={}",
                    suspectedIntent, maskEmail(consultantEmail));
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Cette opération n'est pas autorisée depuis le portail consultant. " +
                    "Seule la saisie de notes de frais est acceptée ici.");
        }

        // ── Garde 2 (LLM sémantique, toutes langues) : délégué à ScopeGuardService ─
        if (!scopeGuardService.isSafe(normalizedPayload)) {
            log.warn("🚫 [SCOPE LLM] Texte hors périmètre bloqué après classification sémantique, consultant={}",
                    maskEmail(consultantEmail));
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Cette opération n'est pas autorisée depuis le portail consultant. " +
                    "Seule la saisie de notes de frais est acceptée ici.");
        }

        ReasoningResult result = reasoningService.process(normalizedPayload, consultantEmail);

        // ── Garde post-LLM : bloquer les intents hors périmètre même si l'IA les a classifiés ─
        if (!CONSULTANT_ALLOWED_INTENTS.contains(result.getType())) {
            log.warn("🚫 [SCOPE] Intent '{}' hors périmètre consultant bloqué après classification, consultant={}",
                    result.getType(), maskEmail(consultantEmail));
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Cette opération n'est pas autorisée depuis le portail consultant. " +
                    "Seule la saisie de notes de frais est acceptée ici.");
        }
        try {
            log.info("✅ /reasoning/analyze result: {}", objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            log.info("✅ /reasoning/analyze result: type={} status={} expenses={}",
                    result.getType(), result.getStatus(),
                    result.getExpenses() != null ? result.getExpenses().size() : 0);
        }
        return result;
    }

    /**
     * Détecte rapidement un intent de suppression ou hors-périmètre sans appel LLM.
     * Retourne un label descriptif si c'est suspect, null si le texte semble légitime.
     * Couvre : opérations destructives (FR+EN) + requêtes sur les factures/clients
     *          qui n'ont pas leur place dans un contexte "notes de frais".
     */
    private String detectDeleteIntent(String payload) {
        if (payload == null || payload.isBlank()) return null;

        // Cas 1 : JSON d'intent déjà structuré (pré-classifié côté frontend)
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (node.has(FIELD_INTENT)) {
                String intent = node.get(FIELD_INTENT).asText("");
                if (intent.startsWith("delete_") || "generate_invoice".equals(intent)) {
                    return intent;
                }
            }
        } catch (Exception ignored) { /* payload texte brut */ }

        String lower = payload.toLowerCase(java.util.Locale.ROOT);

        // Cas 2 : mots-clés destructifs FR
        if (lower.contains("supprim") || lower.contains("efface") || lower.contains("enlev")
                || lower.contains("retir")  || lower.contains("vider") || lower.contains("détruir")
                || lower.contains("nettoy") || lower.contains("écras") || lower.contains("annul")) {
            return "delete_suspected_fr";
        }

        // Cas 3 : mots-clés destructifs EN
        if (lower.contains("delete") || lower.contains("remove") || lower.contains("erase")
                || lower.contains("wipe")   || lower.contains("destroy") || lower.contains("drop")
                || lower.contains("truncat") || lower.contains("purge")  || lower.contains("clear all")) {
            return "delete_suspected_en";
        }

        // Cas 4 : croisement de domaines — gestion de factures (pas un simple "j'ai payé une facture")
        // On bloque : "les factures de mai", "toutes les factures", "factures du mois"
        // On NE bloque PAS : "une facture de restaurant", "la facture du taxi"
        if ((lower.contains("factures") || lower.contains("invoices"))
                && (lower.contains(" du mois") || lower.contains(" de mai") || lower.contains(" de juin")
                    || lower.contains(" toutes") || lower.contains("toutes les") || lower.contains("all invoice"))) {
            return "cross_domain_invoice";
        }

        return null;
    }

    private String extractField(String payload, String field) {
        if (payload == null || payload.isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (node.hasNonNull(field)) {
                return node.get(field).asText(null);
            }
        } catch (Exception ignored) { /* payload texte brut */ }
        return null;
    }

    private static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***";
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2) return "***" + domain;
        return local.charAt(0) + "***" + local.charAt(local.length() - 1) + domain;
    }

    private String normalizePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (!node.isObject()) {
                return payload;
            }
            // Cas 1: requête simple de type {"text":"..."} -> on extrait le texte métier.
            if (node.hasNonNull("text") && !node.has(FIELD_INTENT)) {
                return node.get("text").asText(payload);
            }
            // Cas 2: JSON d'intent déjà structuré -> on le garde tel quel pour préserver entities/ids/month.
            return payload;
        } catch (Exception ignored) {
            return payload;
        }
    }
}
