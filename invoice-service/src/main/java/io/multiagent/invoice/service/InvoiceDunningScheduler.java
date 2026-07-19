package io.multiagent.invoice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Déclencheur quotidien des relances de factures impayées.
 * La logique vit dans InvoiceDunningService (réutilisée par la relance manuelle).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceDunningScheduler {

    private final InvoiceDunningService dunningService;

    /** Chaque jour à 08h00 (configurable via dunning.cron). */
    @Scheduled(cron = "${dunning.cron:0 0 8 * * *}")
    public void scheduledRun() {
        if (!dunningService.isEnabled()) {
            log.info("[Dunning] Relances automatiques désactivées (dunning.enabled=false)");
            return;
        }
        var results = dunningService.runAll();
        long sent = results.stream().filter(r -> "SENT".equals(r.get("status"))).count();
        log.info("[Dunning] Exécution terminée — {} relance(s) envoyée(s) sur {} facture(s) traitée(s)",
                sent, results.size());
    }
}
