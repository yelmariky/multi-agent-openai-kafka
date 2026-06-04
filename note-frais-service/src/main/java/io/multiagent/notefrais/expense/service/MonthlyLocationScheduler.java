package io.multiagent.notefrais.expense.service;

import io.multiagent.notefrais.reasoning.NotefraisReasoningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Envoie automatiquement une dépense de type "location" chaque début de mois.
 */
@Service
@EnableScheduling
@Slf4j
@RequiredArgsConstructor
public class MonthlyLocationScheduler {

    private final NotefraisReasoningService reasoningService;

    @Value("${ai-core.monthly-location.enabled:true}")
    private boolean enabled;

    @Value("${ai-core.monthly-location.text:je loue pour la domiciliation a 450 EUR ce mois-ci}")
    private String messageText;

    /**
     * Cron : chaque 1er du mois à 08h00 UTC.
     */
    @Scheduled(cron = "0 0 8 1 * ?")
    public void sendMonthlyLocation() {
        if (!enabled) {
            return;
        }
        try {
            reasoningService.process(messageText, null);
            log.info("MonthlyLocationScheduler → message traité directement : {}", messageText);
        } catch (Exception e) {
            log.error("MonthlyLocationScheduler échec : {}", e.getMessage(), e);
        }
    }
}
