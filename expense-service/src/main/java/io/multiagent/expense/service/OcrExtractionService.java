package io.multiagent.expense.service;

import io.multiagent.expense.client.LLMAIClient;
import io.multiagent.expense.util.DateProvider;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class OcrExtractionService {

    private final LLMAIClient llm;
    private final DateProvider dateProvider;
    private final VisionExtractionService vision;

    @Value("${AI_CORE_PROMPT_OCR_SINGLE_EXPENSE:}")
    private String ocrPromptEnv;

    private String ocrPromptTemplate;

    private static final int    MIN_OCR_LENGTH = 50;
    private static final double MIN_WORD_RATIO  = 0.30;
    // Un OCR utile doit contenir au moins un montant chiffré (ex: 11,00 ou 11.00)
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\d+[.,]\\d{2}");

    @PostConstruct
    public void loadPrompt() {
        try {
            if (ocrPromptEnv != null && !ocrPromptEnv.isBlank()) {
                ocrPromptTemplate = ocrPromptEnv;
            } else {
                throw new IllegalStateException("AI_CORE_PROMPT_OCR_SINGLE_EXPENSE non défini (ConfigMap/env requis)");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Impossible de charger le prompt OCR", e);
        }
    }

    /** Extraction depuis texte OCR (voie normale). */
    public String extractExpenseJson(String ocrText) {
        String today = dateProvider.todayUtc().toString();
        String augmented = prependDateHint(ocrText);
        // Échapper les % littéraux du template avant substitution
        String systemPrompt = ocrPromptTemplate.replace("%", "%%").replace("%%s", "%s").formatted(today);
        return llm.extractJSON(systemPrompt, augmented);
    }

    /**
     * Extraction avec fallback vision automatique.
     * Si l'OCR est de mauvaise qualité, l'image est analysée directement via OpenAI Vision.
     * Post-traitement systématique : correction d'année et validation basique.
     */
    public String extractExpenseJson(String ocrText, Path imagePath) {
        String raw;
        if (isOcrPoor(ocrText) && imagePath != null) {
            log.warn("🔭 Qualité OCR insuffisante (len={}) — fallback Vision API pour {}", ocrText == null ? 0 : ocrText.length(), imagePath.getFileName());
            try {
                String today        = dateProvider.todayUtc().toString();
                String systemPrompt = ocrPromptTemplate.replace("%", "%%").replace("%%s", "%s").formatted(today);
                raw = vision.extractFromImage(imagePath, systemPrompt);
            } catch (Exception e) {
                log.error("⚠️ Vision fallback échoué, retour sur OCR texte : {}", e.getMessage());
                raw = extractExpenseJson(ocrText);
            }
        } else {
            raw = extractExpenseJson(ocrText);
        }
        return sanitizeExtractedJson(raw);
    }

    /**
     * Corrige les erreurs courantes du LLM :
     *  - Année < 2020 sur un ticket récent → incrémente de 20 (ex: 2006 → 2026)
     */
    String sanitizeExtractedJson(String json) {
        if (json == null || json.isBlank()) return json;
        int currentYear = dateProvider.todayUtc().getYear();
        // Corrige les dates JSON dont l'année est < 2020 par un décalage exact de 20 ans (ex: 2006 → 2026)
        java.util.regex.Pattern datePattern = java.util.regex.Pattern.compile("\"(\\d{4})-(\\d{2})-(\\d{2})\"");
        java.util.regex.Matcher matcher = datePattern.matcher(json);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            int year = Integer.parseInt(matcher.group(1));
            if (year < 2020 && (currentYear - year) >= 18 && (currentYear - year) <= 22) {
                // Correction : 2006 → 2026 (décalage exact de 20 ans)
                int correctedYear = year + 20;
                log.warn("sanitizeExtractedJson: année corrigée {} → {}", year, correctedYear);
                matcher.appendReplacement(sb, '"' + correctedYear + "-" + matcher.group(2) + "-" + matcher.group(3) + '"');
            } else {
                matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** Retourne true si le texte OCR est trop court, trop bruité, ou sans montant détectable. */
    private boolean isOcrPoor(String text) {
        if (text == null || text.length() < MIN_OCR_LENGTH) return true;
        // Critère principal : absence de montant (ex: "11,00" ou "11.00") → OCR inutilisable
        if (!AMOUNT_PATTERN.matcher(text).find()) return true;
        // Critère secondaire : ratio de mots alphabétiques lisibles trop bas
        String[] tokens = text.split("\\s+");
        if (tokens.length == 0) return true;
        long goodWords = 0;
        for (String t : tokens) {
            if (t.matches("[A-Za-zÀ-ÿ]{3,}")) goodWords++;
        }
        return (double) goodWords / tokens.length < MIN_WORD_RATIO;
    }

    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(\\d{2}[/-]\\d{2}[/-]\\d{4})(?:\\s+\\d{2}:\\d{2}:\\d{2})?|"
                    + "(\\d{4}-\\d{2}-\\d{2})(?:\\s+\\d{2}:\\d{2}:\\d{2})?",
            Pattern.CASE_INSENSITIVE);

    private String prependDateHint(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        Matcher m = DATE_PATTERN.matcher(text);
        if (m.find()) {
            String dateFound = m.group().trim();
            return "Date détectée (ne pas remplacer) : " + dateFound + "\n" + text;
        }
        return text;
    }
}
