package io.multiagent.invoice.service;

import io.multiagent.invoice.entity.InvoiceDunningLogEntity;
import io.multiagent.invoice.entity.InvoiceEntity;
import io.multiagent.invoice.entity.SellerProfileEntity;
import io.multiagent.invoice.infrastructure.tenant.TenantContext;
import io.multiagent.invoice.repository.InvoiceDunningLogJpaRepository;
import io.multiagent.invoice.repository.InvoiceJpaRepository;
import io.multiagent.invoice.repository.SellerProfileJpaRepository;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Relances automatiques de factures impayées (dunning).
 *
 * Échéancier (jours après la date d'échéance) :
 *   R1 (J+3)  — rappel courtois
 *   R2 (J+15) — relance ferme (rappel des pénalités)
 *   R3 (J+30) — mise en demeure (art. L441-10 C. com.)
 *
 * Périmètre : factures ENVOYEE ou EN_RETARD dont l'échéance est dépassée.
 * Stop : facture PAYEE → aucune relance. Un seul envoi par palier (trace dunning_log).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceDunningService {

    private static final int[] STAGE_DAYS = {3, 15, 30};  // seuils R1/R2/R3
    private static final DateTimeFormatter FR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final InvoiceJpaRepository invoiceRepo;
    private final InvoiceDunningLogJpaRepository dunningRepo;
    private final SellerProfileJpaRepository sellerProfileRepo;
    private final JdbcTemplate jdbcTemplate;
    private final JavaMailSender mailSender;

    @Value("${dunning.enabled:false}")
    private boolean enabled;

    @Value("${dunning.from:noreply@ia-insightservices.fr}")
    private String fromAddress;

    public boolean isEnabled() {
        return enabled;
    }

    /** Parcourt tous les tenants et relance les factures dues. @return résumé par facture. */
    public List<Map<String, Object>> runAll() {
        List<Map<String, Object>> results = new ArrayList<>();
        List<UUID> tenantIds = jdbcTemplate.queryForList("SELECT id FROM organization", UUID.class);
        for (UUID tenantId : tenantIds) {
            try {
                TenantContext.set(tenantId, null);
                results.addAll(runForTenant(tenantId));
            } catch (Exception e) {
                log.error("[Dunning] Erreur tenant={} : {}", tenantId, e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        return results;
    }

    private List<Map<String, Object>> runForTenant(UUID tenantId) {
        List<Map<String, Object>> results = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (InvoiceEntity inv : invoiceRepo.findByTenantId(tenantId)) {
            if (!isDunnable(inv, today)) continue;
            int daysLate = (int) ChronoUnit.DAYS.between(inv.getPaymentDueDate(), today);
            int stage = dueStage(daysLate);
            if (stage == 0) continue;                                   // pas encore J+3
            if (dunningRepo.existsByInvoiceIdAndStage(inv.getId(), stage)) continue;  // palier déjà envoyé

            Map<String, Object> r = sendDunning(tenantId, inv, stage, daysLate);
            results.add(r);
        }
        return results;
    }

    /** Relance manuelle d'une facture (bouton « Relancer maintenant »). */
    public Map<String, Object> dunManually(UUID tenantId, UUID invoiceId) {
        InvoiceEntity inv = invoiceRepo.findById(invoiceId)
                .filter(i -> tenantId.equals(i.getTenantId()))
                .orElseThrow(() -> new IllegalArgumentException("Facture introuvable"));
        LocalDate today = LocalDate.now();
        if ("PAYEE".equalsIgnoreCase(inv.getPaymentStatus())) {
            return Map.of("invoice", inv.getInvoiceName(), "status", "SKIPPED_PAID");
        }
        int daysLate = inv.getPaymentDueDate() != null
                ? (int) ChronoUnit.DAYS.between(inv.getPaymentDueDate(), today) : 0;
        int stage = Math.max(1, dueStage(daysLate));   // relance manuelle : au moins R1
        return sendDunning(tenantId, inv, stage, Math.max(daysLate, 0));
    }

    // ── Cœur ────────────────────────────────────────────────────────────────

    private Map<String, Object> sendDunning(UUID tenantId, InvoiceEntity inv, int stage, int daysLate) {
        String recipient = resolveClientEmail(tenantId, inv.getClientCompanyName());
        if (recipient == null || recipient.isBlank()) {
            log.warn("[Dunning] {} — pas d'email client pour '{}', relance sautée",
                    inv.getInvoiceName(), inv.getClientCompanyName());
            return Map.of("invoice", inv.getInvoiceName(), "status", "NO_EMAIL",
                    "client", String.valueOf(inv.getClientCompanyName()));
        }
        try {
            sendEmail(recipient, inv, stage, daysLate);
            InvoiceDunningLogEntity logEntry = new InvoiceDunningLogEntity();
            logEntry.setTenantId(tenantId);
            logEntry.setInvoiceId(inv.getId());
            logEntry.setInvoiceName(inv.getInvoiceName());
            logEntry.setStage(stage);
            logEntry.setRecipient(recipient);
            dunningRepo.save(logEntry);

            // Marquer la facture en retard si elle ne l'est pas déjà
            if (!"EN_RETARD".equalsIgnoreCase(inv.getPaymentStatus())) {
                inv.setPaymentStatus("EN_RETARD");
                invoiceRepo.save(inv);
            }
            log.info("[Dunning] R{} envoyée → {} (facture {}, {}j de retard)",
                    stage, recipient, inv.getInvoiceName(), daysLate);
            return Map.of("invoice", inv.getInvoiceName(), "status", "SENT",
                    "stage", stage, "recipient", recipient);
        } catch (Exception e) {
            log.error("[Dunning] Échec envoi facture {} : {}", inv.getInvoiceName(), e.getMessage(), e);
            return Map.of("invoice", inv.getInvoiceName(), "status", "ERROR",
                    "error", String.valueOf(e.getMessage()));
        }
    }

    private boolean isDunnable(InvoiceEntity inv, LocalDate today) {
        if (inv.getPaymentDueDate() == null) return false;
        String st = inv.getPaymentStatus();
        // On ne relance que les factures envoyées / déjà en retard (jamais EN_ATTENTE ni PAYEE)
        boolean sentOrLate = "ENVOYEE".equalsIgnoreCase(st) || "EN_RETARD".equalsIgnoreCase(st);
        return sentOrLate && inv.getPaymentDueDate().isBefore(today);
    }

    /** Palier dû = plus haut seuil atteint (0 si < 3 jours de retard). */
    private int dueStage(int daysLate) {
        int stage = 0;
        for (int i = 0; i < STAGE_DAYS.length; i++) {
            if (daysLate >= STAGE_DAYS[i]) stage = i + 1;
        }
        return stage;
    }

    /** Email de contact du client (table client, propriété d'ai-service, DB partagée). */
    private String resolveClientEmail(UUID tenantId, String clientName) {
        if (clientName == null || clientName.isBlank()) return null;
        List<String> emails = jdbcTemplate.queryForList(
                "SELECT contact_email FROM client WHERE tenant_id = ? AND LOWER(name) = LOWER(?) "
                + "AND contact_email IS NOT NULL AND contact_email <> '' LIMIT 1",
                String.class, tenantId, clientName.trim());
        return emails.isEmpty() ? null : emails.get(0);
    }

    // ── Email ────────────────────────────────────────────────────────────────

    private void sendEmail(String recipient, InvoiceEntity inv, int stage, int daysLate) throws Exception {
        String sellerName = sellerProfileRepo.findByTenantId(inv.getTenantId())
                .map(SellerProfileEntity::getCompanyName).orElse("IA-INSIGHT");
        MimeMessage msg = mailSender.createMimeMessage();
        MimeMessageHelper h = new MimeMessageHelper(msg, true, "UTF-8");
        h.setFrom(fromAddress);
        h.setTo(recipient);
        h.setSubject(subject(stage, inv));
        h.setText(buildHtml(inv, stage, daysLate, sellerName), true);
        mailSender.send(msg);
    }

    private String subject(int stage, InvoiceEntity inv) {
        String n = inv.getInvoiceName();
        return switch (stage) {
            case 1 -> "Rappel — facture " + n + " arrivée à échéance";
            case 2 -> "Relance — facture " + n + " impayée";
            default -> "Mise en demeure — facture " + n;
        };
    }

    private String buildHtml(InvoiceEntity inv, int stage, int daysLate, String sellerName) {
        String amount = inv.getTotalTtc() != null ? inv.getTotalTtc().toPlainString() + " EUR TTC" : "—";
        String due    = inv.getPaymentDueDate() != null ? inv.getPaymentDueDate().format(FR_DATE) : "—";
        String headerColor = switch (stage) { case 1 -> "#2563eb"; case 2 -> "#d97706"; default -> "#dc2626"; };
        String title = switch (stage) {
            case 1 -> "Rappel d'échéance";
            case 2 -> "Relance — facture impayée";
            default -> "Mise en demeure de payer";
        };
        String body = switch (stage) {
            case 1 -> "Sauf erreur de notre part, la facture <strong>" + inv.getInvoiceName()
                    + "</strong> d'un montant de <strong>" + amount + "</strong>, arrivée à échéance le <strong>"
                    + due + "</strong>, demeure impayée. Nous vous remercions de bien vouloir procéder à son règlement.";
            case 2 -> "Malgré notre premier rappel, la facture <strong>" + inv.getInvoiceName()
                    + "</strong> (" + amount + "), échue depuis " + daysLate + " jours, reste impayée. "
                    + "Nous vous invitons à la régler sans délai. À défaut, des pénalités de retard seront appliquées.";
            default -> "La facture <strong>" + inv.getInvoiceName() + "</strong> (" + amount
                    + "), échue depuis " + daysLate + " jours, demeure impayée malgré nos relances. "
                    + "Conformément à l'article L441-10 du Code de commerce, des pénalités de retard (trois fois le taux "
                    + "d'intérêt légal) ainsi qu'une indemnité forfaitaire de recouvrement de 40 EUR sont exigibles. "
                    + "Nous vous mettons en demeure de régler cette facture sous 8 jours, faute de quoi le recouvrement "
                    + "sera engagé et l'accès au service pourra être suspendu.";
        };
        return """
            <!DOCTYPE html><html lang="fr"><head><meta charset="UTF-8"></head>
            <body style="margin:0;padding:0;background:#f4f6f9;font-family:'Segoe UI',Arial,sans-serif;">
              <table width="100%%" cellspacing="0" cellpadding="0" style="background:#f4f6f9;padding:32px 0;">
              <tr><td align="center">
              <table width="600" cellspacing="0" cellpadding="0"
                     style="background:#fff;border-radius:8px;box-shadow:0 2px 8px rgba(0,0,0,.08);max-width:600px;width:100%%;">
                <tr><td style="background:%s;padding:26px 40px;text-align:center;">
                  <p style="margin:0;font-size:19px;font-weight:700;color:#fff;">%s</p>
                </td></tr>
                <tr><td style="padding:32px 40px 24px;">
                  <p style="margin:0 0 18px;font-size:14px;color:#475569;line-height:1.7;">%s</p>
                  <table width="100%%" cellspacing="0" cellpadding="0" style="margin:0 0 22px;border:1px solid #e8ecf0;border-radius:6px;">
                    <tr><td style="padding:12px 16px;font-size:13px;color:#64748b;">Facture</td>
                        <td style="padding:12px 16px;font-size:13px;color:#0f172a;font-weight:600;text-align:right;">%s</td></tr>
                    <tr><td style="padding:12px 16px;font-size:13px;color:#64748b;border-top:1px solid #e8ecf0;">Montant</td>
                        <td style="padding:12px 16px;font-size:13px;color:#0f172a;font-weight:600;text-align:right;border-top:1px solid #e8ecf0;">%s</td></tr>
                    <tr><td style="padding:12px 16px;font-size:13px;color:#64748b;border-top:1px solid #e8ecf0;">Échéance</td>
                        <td style="padding:12px 16px;font-size:13px;color:#0f172a;font-weight:600;text-align:right;border-top:1px solid #e8ecf0;">%s</td></tr>
                  </table>
                  <p style="margin:0;font-size:13px;color:#94a3b8;">Si votre règlement a été effectué entre-temps, merci de ne pas tenir compte de ce message.</p>
                </td></tr>
                <tr><td style="background:#f8fafc;padding:16px 40px;border-top:1px solid #e8ecf0;text-align:center;">
                  <p style="margin:0;font-size:11px;color:#94a3b8;">%s · Message automatique</p>
                </td></tr>
              </table></td></tr></table>
            </body></html>
            """.formatted(headerColor, title, body,
                inv.getInvoiceName(), amount, due, sellerName);
    }
}
