package io.multiagent.activity.cra.scheduler;

import io.multiagent.activity.cra.entity.CraEntity;
import io.multiagent.activity.cra.repository.CraJpaRepository;
import io.multiagent.activity.infrastructure.tenant.TenantContext;
import io.multiagent.activity.organization.entity.Client;
import io.multiagent.activity.organization.entity.Organization;
import io.multiagent.activity.organization.repository.ClientRepository;
import io.multiagent.activity.organization.repository.OrganizationRepository;
import io.multiagent.activity.settings.entity.ConsultantProfileEntity;
import io.multiagent.activity.settings.entity.SellerProfileEntity;
import io.multiagent.activity.settings.repository.ConsultantProfileJpaRepository;
import io.multiagent.activity.settings.repository.SellerProfileJpaRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Scheduler de relances CRA mensuel.
 *
 * Règles métier :
 *   J20 (08h00) — 1ère relance douce aux consultants sans CRA soumis
 *   J22 (08h00) — 2ème relance avec rappel de l'échéance
 *   J24 (08h00) — 3ème relance urgente (dernier rappel)
 *   J26 (08h00) — Escalade vers l'admin ESN si week-end décalé au lundi suivant
 *
 * Stop automatique : un consultant dont le CRA est SOUMIS ou VALIDE
 * ne reçoit jamais de relance, quelle que soit la date.
 *
 * Décision week-end :
 *   Relances J20/J22/J24 — envoyées le jour calendaire exact.
 *   Escalade J26         — si J26 est samedi ou dimanche, le cron du lundi
 *                          détecte le décalage et envoie l'alerte ce lundi matin.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CraReminderScheduler {

    private final CraJpaRepository craRepo;
    private final ConsultantProfileJpaRepository consultantRepo;
    private final SellerProfileJpaRepository sellerProfileRepo;
    private final ClientRepository clientRepo;
    private final OrganizationRepository orgRepo;
    private final JavaMailSender mailSender;

    @Value("${cra.reminder.enabled:true}")
    private boolean enabled;

    @Value("${cra.reminder.from:noreply@ia-insight.fr}")
    private String fromAddress;

    @Value("${cra.reminder.frontend-url:http://localhost:3001}")
    private String frontendUrl;

    private static final DateTimeFormatter MONTH_FORMATTER =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRENCH);
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.FRENCH);

    // ─────────────────────────────────────────────────────────
    // CRONS RELANCES CONSULTANT
    // ─────────────────────────────────────────────────────────

    /** J20 — 1ère relance douce */
    @Scheduled(cron = "0 0 8 20 * ?")
    public void reminderDay20() {
        sendReminders(ReminderType.PREMIERE);
    }

    /** J22 — 2ème relance avec rappel échéance */
    @Scheduled(cron = "0 0 8 22 * ?")
    public void reminderDay22() {
        sendReminders(ReminderType.DEUXIEME);
    }

    /** J24 — 3ème relance urgente */
    @Scheduled(cron = "0 0 8 24 * ?")
    public void reminderDay24() {
        sendReminders(ReminderType.URGENTE);
    }

    // ─────────────────────────────────────────────────────────
    // CRONS ESCALADE ADMIN (J26 ou lundi de report)
    // ─────────────────────────────────────────────────────────

    /** J26 — Escalade si J26 est un jour ouvré */
    @Scheduled(cron = "0 0 8 26 * ?")
    public void escalationDay26() {
        LocalDate today = LocalDate.now();
        if (today.getDayOfWeek() == DayOfWeek.SATURDAY
                || today.getDayOfWeek() == DayOfWeek.SUNDAY) {
            // Week-end : le cron du lundi prend le relais
            log.info("[CraEscalation] J26 tombe un week-end — report au lundi");
            return;
        }
        sendEscalation();
    }

    /**
     * Lundi matin — vérifie si le 26 du mois précédent était un week-end.
     * Si oui, envoie l'escalade décalée ce lundi.
     */
    @Scheduled(cron = "0 0 8 * * MON")
    public void escalationMondayFallback() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday  = today.minusDays(1); // dimanche éventuel
        LocalDate twoDaysAgo = today.minusDays(2); // samedi éventuel

        boolean saturdayWas26 = twoDaysAgo.getDayOfMonth() == 26;
        boolean sundayWas26   = yesterday.getDayOfMonth()  == 26;

        if (saturdayWas26 || sundayWas26) {
            log.info("[CraEscalation] Lundi de report — le 26 était un week-end, envoi escalade");
            sendEscalation();
        }
    }

    // ─────────────────────────────────────────────────────────
    // LOGIQUE PRINCIPALE
    // ─────────────────────────────────────────────────────────

    private void sendReminders(ReminderType type) {
        if (!enabled) return;

        YearMonth month  = YearMonth.now();
        String monthStr  = month.toString();
        String monthLabel = month.format(MONTH_FORMATTER);
        String deadline  = month.atDay(25).format(DATE_FORMATTER);

        log.info("[CraReminder] {} — mois={}", type, monthStr);

        forEachActiveOrg((org, tenantId) -> {
            List<ConsultantProfileEntity> consultants =
                    consultantRepo.findByTenantIdAndActiveTrue(tenantId);

            Set<String> submittedEmails = submittedCraEmails(tenantId, monthStr);

            int sent = 0;
            for (ConsultantProfileEntity c : consultants) {
                if (c.getEmail() == null || c.getEmail().isBlank()) continue;
                if (submittedEmails.contains(c.getEmail().toLowerCase(Locale.ROOT))) continue;

                String status = currentStatus(tenantId, c.getEmail(), monthStr);
                try {
                    String realm = org.getKeycloakRealm();
                    sendReminderEmail(c, monthLabel, deadline, status, realm, type);
                    sent++;
                    log.info("[CraReminder] {} → {} (status={})", type, c.getEmail(),
                            status == null ? "ABSENT" : status);
                } catch (Exception e) {
                    log.error("[CraReminder] Échec envoi {} → {} : {}", type, c.getEmail(), e.getMessage());
                }
            }
            log.info("[CraReminder] {} — org={} : {}/{} relance(s)", type, org.getKeycloakRealm(), sent, consultants.size());
        });
    }

    private void sendEscalation() {
        if (!enabled) return;

        YearMonth month   = YearMonth.now();
        String monthStr   = month.toString();
        String monthLabel = month.format(MONTH_FORMATTER);

        log.info("[CraEscalation] Envoi alerte admin — mois={}", monthStr);

        forEachActiveOrg((org, tenantId) -> {
            List<ConsultantProfileEntity> consultants =
                    consultantRepo.findByTenantIdAndActiveTrue(tenantId);

            Set<String> submittedEmails = submittedCraEmails(tenantId, monthStr);

            List<ConsultantProfileEntity> missing = consultants.stream()
                    .filter(c -> c.getEmail() != null && !c.getEmail().isBlank())
                    .filter(c -> !submittedEmails.contains(c.getEmail().toLowerCase(Locale.ROOT)))
                    .toList();

            if (missing.isEmpty()) {
                log.info("[CraEscalation] org={} — tous les CRA soumis, pas d'alerte", org.getKeycloakRealm());
                return;
            }

            // Destinataires escalade :
            // 1) Email admin ESN (SellerProfile)
            // 2) ContactEmail des clients actifs dont des consultants manquants sont rattachés
            Set<String> recipients = new LinkedHashSet<>();
            sellerProfileRepo.findByTenantId(tenantId)
                    .map(SellerProfileEntity::getEmail)
                    .filter(e -> e != null && !e.isBlank())
                    .ifPresent(recipients::add);

            clientRepo.findByTenantIdAndActiveTrue(tenantId).stream()
                    .map(Client::getContactEmail)
                    .filter(e -> e != null && !e.isBlank())
                    .forEach(recipients::add);

            for (String recipient : recipients) {
                try {
                    sendEscalationEmail(recipient, missing, monthLabel, org.getKeycloakRealm());
                    log.info("[CraEscalation] Alerte envoyée → {} ({} CRA manquant(s))",
                            recipient, missing.size());
                } catch (Exception e) {
                    log.error("[CraEscalation] Échec envoi alerte → {} : {}", recipient, e.getMessage());
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────
    // HELPERS DB
    // ─────────────────────────────────────────────────────────

    private Set<String> submittedCraEmails(UUID tenantId, String monthStr) {
        return craRepo.findByTenantIdAndBillingMonth(tenantId, monthStr).stream()
                .filter(c -> "SOUMIS".equals(c.getStatus()) || "VALIDE".equals(c.getStatus()))
                .map(c -> c.getConsultant().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private String currentStatus(UUID tenantId, String email, String monthStr) {
        return craRepo.findByTenantIdAndConsultantIgnoreCaseAndBillingMonth(tenantId, email, monthStr)
                .map(CraEntity::getStatus)
                .orElse(null);
    }

    @FunctionalInterface
    interface OrgConsumer {
        void accept(Organization org, UUID tenantId);
    }

    private void forEachActiveOrg(OrgConsumer consumer) {
        orgRepo.findAll().forEach(org -> {
            try {
                TenantContext.set(org.getId(), org.getKeycloakRealm());
                consumer.accept(org, org.getId());
            } catch (Exception e) {
                log.error("[CraReminder] Erreur org={}: {}", org.getKeycloakRealm(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        });
    }

    // ─────────────────────────────────────────────────────────
    // TEMPLATES EMAIL
    // ─────────────────────────────────────────────────────────

    private void sendReminderEmail(ConsultantProfileEntity consultant,
                                   String monthLabel, String deadline,
                                   String status, String realm,
                                   ReminderType type) throws MessagingException {
        MimeMessage msg = mailSender.createMimeMessage();
        MimeMessageHelper h = new MimeMessageHelper(msg, true, "UTF-8");
        h.setFrom(fromAddress);
        h.setTo(consultant.getEmail());
        h.setSubject(type.subject(monthLabel));
        h.setText(buildReminderHtml(consultant, monthLabel, deadline, status, realm, type), true);
        mailSender.send(msg);
    }

    private void sendEscalationEmail(String recipient,
                                     List<ConsultantProfileEntity> missing,
                                     String monthLabel, String realm) throws MessagingException {
        MimeMessage msg = mailSender.createMimeMessage();
        MimeMessageHelper h = new MimeMessageHelper(msg, true, "UTF-8");
        h.setFrom(fromAddress);
        h.setTo(recipient);
        h.setSubject("[ALERTE] " + missing.size() + " CRA manquant(s) — " + monthLabel);
        h.setText(buildEscalationHtml(missing, monthLabel, realm), true);
        mailSender.send(msg);
    }

    private String buildReminderHtml(ConsultantProfileEntity consultant,
                                     String monthLabel, String deadline,
                                     String status, String realm,
                                     ReminderType type) {
        String firstName  = firstName(consultant.getName(), consultant.getEmail());
        String statusNote = statusNote(status, monthLabel);
        String craUrl     = frontendUrl + "/" + realm + "/";
        String urgencyColor = type == ReminderType.URGENTE ? "#dc2626" : "#d97706";
        String urgencyBg    = type == ReminderType.URGENTE ? "#fef2f2" : "#fff8e1";
        String urgencyBorder = type == ReminderType.URGENTE ? "#dc2626" : "#f59e0b";

        return """
            <!DOCTYPE html><html lang="fr"><head><meta charset="UTF-8"></head>
            <body style="margin:0;padding:0;background:#f4f6f9;font-family:'Segoe UI',Arial,sans-serif;">
              <table width="100%%" cellspacing="0" cellpadding="0" style="background:#f4f6f9;padding:32px 0;">
              <tr><td align="center">
              <table width="600" cellspacing="0" cellpadding="0"
                     style="background:#fff;border-radius:8px;box-shadow:0 2px 8px rgba(0,0,0,.08);max-width:600px;width:100%%;">

                <tr><td style="background:linear-gradient(135deg,#0f172a 0%%,#1e3a8a 100%%);padding:28px 40px;text-align:center;">
                  <p style="margin:0;font-size:20px;font-weight:700;color:#fff;">%s — Rappel CRA %s</p>
                </td></tr>

                <tr><td style="padding:32px 40px 20px;">
                  <p style="margin:0 0 16px;font-size:15px;color:#1a1a2e;font-weight:600;">Bonjour %s,</p>
                  <p style="margin:0 0 14px;font-size:14px;color:#475569;line-height:1.65;">%s</p>
                  <p style="margin:0 0 24px;font-size:14px;color:#475569;line-height:1.65;">
                    Merci de finaliser et soumettre votre CRA
                    <strong style="color:#0f172a;">avant le %s</strong>.
                  </p>

                  <table width="100%%" cellspacing="0" cellpadding="0" style="margin-bottom:24px;">
                    <tr><td style="background:%s;border-left:4px solid %s;border-radius:4px;padding:13px 16px;">
                      <p style="margin:0;font-size:13px;color:%s;line-height:1.5;">%s</p>
                    </td></tr>
                  </table>

                  <table cellspacing="0" cellpadding="0" style="margin:0 auto 28px;">
                    <tr><td align="center" style="background:#2563eb;border-radius:6px;">
                      <a href="%s" style="display:inline-block;padding:13px 34px;font-size:14px;font-weight:600;color:#fff;text-decoration:none;">
                        Saisir mon CRA →
                      </a>
                    </td></tr>
                  </table>

                  <p style="margin:0;font-size:13px;color:#94a3b8;">En cas de difficulté, contactez votre responsable ou l'équipe administrative.</p>
                </td></tr>

                <tr><td style="background:#f8fafc;padding:16px 40px;border-top:1px solid #e8ecf0;text-align:center;">
                  <p style="margin:0;font-size:11px;color:#94a3b8;">Message automatique — ne pas répondre · IA-INSIGHT Platform</p>
                </td></tr>

              </table></td></tr></table>
            </body></html>
            """.formatted(
                type.label(), monthLabel,
                firstName,
                statusNote,
                deadline,
                urgencyBg, urgencyBorder, urgencyColor, type.urgencyNote(monthLabel, deadline),
                craUrl
        );
    }

    private String buildEscalationHtml(List<ConsultantProfileEntity> missing,
                                       String monthLabel, String realm) {
        String adminUrl = frontendUrl.replace("3001", "3000") + "/" + realm + "/";
        UUID tenantId = missing.isEmpty() ? null : missing.get(0).getTenantId();
        String monthStr = YearMonth.now().minusMonths(0).toString();
        StringBuilder rows = new StringBuilder();
        for (ConsultantProfileEntity c : missing) {
            String rawStatus = tenantId != null
                    ? currentStatus(tenantId, c.getEmail(), monthStr)
                    : null;
            String status = rawStatus == null ? "Non commencé"
                    : switch (rawStatus) {
                        case "BROUILLON" -> "Brouillon non soumis";
                        case "REFUSE"    -> "Refusé — correction requise";
                        default          -> rawStatus;
                    };
            rows.append("""
                <tr>
                  <td style="padding:9px 14px;border-bottom:1px solid #e8ecf0;">%s</td>
                  <td style="padding:9px 14px;border-bottom:1px solid #e8ecf0;color:#94a3b8;">%s</td>
                  <td style="padding:9px 14px;border-bottom:1px solid #e8ecf0;">
                    <span style="background:#fee2e2;color:#b91c1c;border-radius:12px;padding:2px 10px;font-size:12px;font-weight:600;">%s</span>
                  </td>
                </tr>
                """.formatted(
                    c.getName() != null ? c.getName() : c.getEmail(),
                    c.getEmail(),
                    status
            ));
        }

        return """
            <!DOCTYPE html><html lang="fr"><head><meta charset="UTF-8"></head>
            <body style="margin:0;padding:0;background:#f4f6f9;font-family:'Segoe UI',Arial,sans-serif;">
              <table width="100%%" cellspacing="0" cellpadding="0" style="background:#f4f6f9;padding:32px 0;">
              <tr><td align="center">
              <table width="640" cellspacing="0" cellpadding="0"
                     style="background:#fff;border-radius:8px;box-shadow:0 2px 8px rgba(0,0,0,.08);max-width:640px;width:100%%;">

                <tr><td style="background:linear-gradient(135deg,#7c2d12 0%%,#dc2626 100%%);padding:28px 40px;text-align:center;">
                  <p style="margin:0;font-size:18px;font-weight:700;color:#fff;">⚠ Alerte CRA — %s</p>
                  <p style="margin:6px 0 0;font-size:13px;color:rgba(255,255,255,.75);">%d consultant(s) n'ont pas soumis leur CRA au 26 du mois</p>
                </td></tr>

                <tr><td style="padding:28px 40px 10px;">
                  <p style="margin:0 0 6px;font-size:14px;color:#475569;line-height:1.65;">
                    Les consultants suivants <strong>n'ont pas soumis leur CRA de %s</strong>
                    malgré les relances automatiques envoyées les 20, 22 et 24 du mois.
                    Une action manuelle est requise.
                  </p>
                </td></tr>

                <tr><td style="padding:0 40px 24px;">
                  <table width="100%%" cellspacing="0" cellpadding="0" style="border:1px solid #e8ecf0;border-radius:8px;overflow:hidden;">
                    <thead>
                      <tr style="background:#0f172a;">
                        <th style="padding:10px 14px;text-align:left;color:#fff;font-size:12px;font-weight:600;letter-spacing:.5px;">NOM</th>
                        <th style="padding:10px 14px;text-align:left;color:#fff;font-size:12px;font-weight:600;letter-spacing:.5px;">EMAIL</th>
                        <th style="padding:10px 14px;text-align:left;color:#fff;font-size:12px;font-weight:600;letter-spacing:.5px;">STATUT</th>
                      </tr>
                    </thead>
                    <tbody>%s</tbody>
                  </table>
                </td></tr>

                <tr><td style="padding:0 40px 28px;">
                  <table cellspacing="0" cellpadding="0">
                    <tr><td style="background:#dc2626;border-radius:6px;">
                      <a href="%s" style="display:inline-block;padding:12px 28px;font-size:13px;font-weight:600;color:#fff;text-decoration:none;">
                        Accéder au dashboard admin →
                      </a>
                    </td></tr>
                  </table>
                </td></tr>

                <tr><td style="background:#f8fafc;padding:16px 40px;border-top:1px solid #e8ecf0;text-align:center;">
                  <p style="margin:0;font-size:11px;color:#94a3b8;">Alerte automatique IA-INSIGHT Platform · Ne pas répondre à ce message</p>
                </td></tr>

              </table></td></tr></table>
            </body></html>
            """.formatted(monthLabel, missing.size(), monthLabel, rows, adminUrl);
    }

    // ─────────────────────────────────────────────────────────
    // UTILITAIRES
    // ─────────────────────────────────────────────────────────

    private String firstName(String name, String email) {
        if (name != null && !name.isBlank()) {
            String first = name.trim().split("\\s+")[0];
            return Character.toUpperCase(first.charAt(0)) + first.substring(1).toLowerCase(Locale.FRENCH);
        }
        return email != null ? email.split("@")[0] : "Consultant";
    }

    private String statusNote(String status, String monthLabel) {
        if (status == null) {
            return "Nous n'avons pas encore reçu votre CRA de <strong>" + monthLabel + "</strong>. "
                    + "Il semble que la saisie n'ait pas encore débuté.";
        }
        return switch (status) {
            case "BROUILLON" -> "Votre CRA de <strong>" + monthLabel + "</strong> est en brouillon "
                    + "mais n'a pas encore été soumis. Pensez à cliquer sur <em>Soumettre</em>.";
            case "REFUSE"    -> "Votre CRA de <strong>" + monthLabel + "</strong> a été refusé "
                    + "et nécessite des corrections. Mettez-le à jour et soumettez-le à nouveau.";
            default          -> "Votre CRA de <strong>" + monthLabel + "</strong> est en attente de finalisation.";
        };
    }

    // ─────────────────────────────────────────────────────────
    // ENUM
    // ─────────────────────────────────────────────────────────

    enum ReminderType {
        PREMIERE {
            @Override public String label()   { return "1ère relance"; }
            @Override public String subject(String m) { return "[Rappel] Votre CRA de " + m + " est en attente"; }
            @Override public String urgencyNote(String m, String d) {
                return "⏰ Pensez à soumettre votre CRA avant le " + d + " pour garantir le traitement de votre paiement.";
            }
        },
        DEUXIEME {
            @Override public String label()   { return "2ème relance"; }
            @Override public String subject(String m) { return "[Rappel] CRA " + m + " — échéance approchante"; }
            @Override public String urgencyNote(String m, String d) {
                return "⚠️ L'échéance approche. Votre CRA doit être soumis avant le " + d + ".";
            }
        },
        URGENTE {
            @Override public String label()   { return "3ème relance — URGENT"; }
            @Override public String subject(String m) { return "[URGENT] Dernier rappel — CRA " + m + " non soumis"; }
            @Override public String urgencyNote(String m, String d) {
                return "🚨 Dernier rappel. Sans soumission avant le " + d + ", votre paiement pour " + m + " pourrait être retardé.";
            }
        };

        public abstract String label();
        public abstract String subject(String monthLabel);
        public abstract String urgencyNote(String monthLabel, String deadline);
    }
}
