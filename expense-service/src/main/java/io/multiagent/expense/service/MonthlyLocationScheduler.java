package io.multiagent.expense.service;

import io.multiagent.expense.reasoning.service.ReasoningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Envoie automatiquement une dépense de type "location" chaque début de mois
 * directement dans le pipeline ReasoningService (classifieur + RAG).
 */
@Service
@EnableScheduling
@Slf4j
@RequiredArgsConstructor
public class MonthlyLocationScheduler {

    private final ReasoningService reasoningService;

    // Désactivé par défaut : ai-service exécute déjà ce job (MONTHLY_LOCATION_ENABLED) — l'activer ici aussi
    // créerait une location en double chaque 1er du mois (et une par réplica, expense-service étant autoscalé).
    // Réactivable via AI_CORE_MONTHLY_LOCATION_ENABLED=true si le job est un jour retiré d'ai-service.
    @Value("${ai-core.monthly-location.enabled:false}")
    private boolean enabled;

    @Value("${MONTHLY_LOCATION_TEXT:}")
    private String messageText;

    /**
     * Cron : chaque 1er du mois à 08h00 UTC.
     */
    @Scheduled(cron = "0 0 8 1 * ?")
    public void sendMonthlyLocation() {
        if (!enabled) {
            return;
        }
        if (messageText == null || messageText.isBlank()) {
            log.warn("⚠️ MonthlyLocationScheduler : MONTHLY_LOCATION_TEXT vide — aucune location générée");
            return;
        }
        try {
            reasoningService.process(messageText, null);
            log.info("📤 MonthlyLocationScheduler → message traité directement : {}", messageText);
        } catch (Exception e) {
            log.error("❌ MonthlyLocationScheduler échec : {}", e.getMessage(), e);
        }
    }
}
