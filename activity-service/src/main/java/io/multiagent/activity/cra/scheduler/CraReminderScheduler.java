package io.multiagent.activity.cra.scheduler;

import io.multiagent.activity.infrastructure.tenant.TenantContext;
import io.multiagent.activity.organization.entity.Organization;
import io.multiagent.activity.organization.repository.OrganizationRepository;
import io.multiagent.activity.service.ActivityDataService;
import io.multiagent.activity.settings.entity.ConsultantProfileEntity;
import io.multiagent.activity.settings.repository.ConsultantProfileJpaRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Batch mensuel — le 24 de chaque mois à 08h00 UTC.
 * Envoie un mail de relance à chaque consultant dont le CRA du mois courant
 * est absent, en BROUILLON ou en REFUSE.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CraReminderScheduler {

    private final OrganizationRepository organizationRepository;
    private final ConsultantProfileJpaRepository consultantProfileRepo;
    private final ActivityDataService activityDataService;
    private final JavaMailSender mailSender;

    @Value("${ai-core.cra-reminder.enabled:true}")
    private boolean enabled;

    @Value("${ai-core.cra-reminder.from:noreply@example.com}")
    private String fromAddress;

    @Value("${ai-core.cra-reminder.frontend-url:http://localhost:3001}")
    private String frontendUrl;

    /** Cron : le 24 de chaque mois à 08h00 UTC. */
    @Scheduled(cron = "0 0 8 24 * ?")
    public void sendCraReminders() {
        if (!enabled) {
            log.info("CraReminderScheduler désactivé (CRA_REMINDER_ENABLED=false)");
            return;
        }

        YearMonth currentMonth = YearMonth.now();
        String monthStr   = currentMonth.toString();                          // "2026-06"
        String monthLabel = currentMonth.format(
                DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRENCH));    // "juin 2026"
        String deadline   = currentMonth.atDay(26)
                .format(DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.FRENCH)); // "26 juin 2026"

        List<Organization> orgs = organizationRepository.findAll().stream()
                .filter(o -> Boolean.TRUE.equals(o.getActive()))
                .toList();

        log.info("CraReminderScheduler — mois={}, {} organisation(s)", monthStr, orgs.size());

        for (Organization org : orgs) {
            try {
                TenantContext.set(org.getId(), org.getKeycloakRealm());
                processOrganization(org, monthStr, monthLabel, deadline);
            } catch (Exception e) {
                log.error("CraReminderScheduler — erreur org={}: {}", org.getName(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
    }

    // -----------------------------------------------------------------------

    private void processOrganization(Organization org, String monthStr,
                                     String monthLabel, String deadline) {
        // Tous les consultants actifs de l'organisation
        List<ConsultantProfileEntity> consultants =
                consultantProfileRepo.findByTenantIdAndActiveTrue(org.getId());

        if (consultants.isEmpty()) return;

        // CRAs déjà existants pour ce mois (status = SOUMIS ou VALIDE → pas de relance)
        List<Map<String, Object>> cras =
                activityDataService.findCrasByPeriod(monthStr, monthStr, null, null);

        Set<String> finalizedEmails = cras.stream()
                .filter(c -> {
                    String s = (String) c.get("status");
                    return "SOUMIS".equals(s) || "VALIDE".equals(s);
                })
                .map(c -> ((String) c.get("consultant")).toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        int sent = 0;
        for (ConsultantProfileEntity consultant : consultants) {
            String email = consultant.getEmail();
            if (email == null || email.isBlank()) continue;
            if (finalizedEmails.contains(email.toLowerCase(Locale.ROOT))) continue;

            // Détermine si c'est un premier oubli (pas de CRA) ou un brouillon/refus
            String currentStatus = cras.stream()
                    .filter(c -> email.equalsIgnoreCase((String) c.get("consultant")))
                    .map(c -> (String) c.get("status"))
                    .findFirst().orElse(null);

            try {
                sendReminderEmail(
                        consultant.getName() != null ? consultant.getName() : email,
                        email,
                        monthLabel,
                        deadline,
                        currentStatus,
                        org.getKeycloakRealm()
                );
                sent++;
                log.info("Relance CRA envoyée → {} (org={}, status={})",
                        email, org.getName(), currentStatus == null ? "ABSENT" : currentStatus);
            } catch (Exception e) {
                log.error("Échec envoi relance CRA → {} : {}", email, e.getMessage());
            }
        }
        log.info("CraReminderScheduler — org={}: {}/{} relance(s) envoyée(s)",
                org.getName(), sent, consultants.size());
    }

    private void sendReminderEmail(String name, String email, String monthLabel,
                                   String deadline, String currentStatus,
                                   String realm) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom(fromAddress);
        helper.setTo(email);
        helper.setSubject("[Action requise] Votre CRA de " + monthLabel + " est en attente");
        helper.setText(buildEmailHtml(name, monthLabel, deadline, currentStatus, realm), true);

        mailSender.send(message);
    }

    // -----------------------------------------------------------------------
    // Template HTML du mail de relance
    // -----------------------------------------------------------------------

    private String buildEmailHtml(String name, String monthLabel, String deadline,
                                   String currentStatus, String realm) {
        String firstName = extractFirstName(name);
        String statusNote = buildStatusNote(currentStatus, monthLabel);
        String craUrl = frontendUrl + "/" + realm + "/";

        return """
                <!DOCTYPE html>
                <html lang="fr">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>Rappel CRA</title>
                </head>
                <body style="margin:0;padding:0;background-color:#f4f6f9;font-family:'Segoe UI',Arial,sans-serif;">
                  <table role="presentation" width="100%%" cellspacing="0" cellpadding="0"
                         style="background-color:#f4f6f9;padding:32px 0;">
                    <tr><td align="center">
                      <table role="presentation" width="600" cellspacing="0" cellpadding="0"
                             style="background:#ffffff;border-radius:8px;overflow:hidden;
                                    box-shadow:0 2px 8px rgba(0,0,0,.08);max-width:600px;width:100%%;">

                        <!-- En-tête -->
                        <tr>
                          <td style="background:linear-gradient(135deg,#1e3a5f 0%%,#2d6a9f 100%%);
                                     padding:32px 40px;text-align:center;">
                            <p style="margin:0;font-size:22px;font-weight:700;color:#ffffff;letter-spacing:.5px;">
                              Rappel — CRA %s
                            </p>
                          </td>
                        </tr>

                        <!-- Corps -->
                        <tr>
                          <td style="padding:36px 40px 24px;">
                            <p style="margin:0 0 20px;font-size:16px;color:#1a1a2e;font-weight:600;">
                              Bonjour %s,
                            </p>
                            <p style="margin:0 0 16px;font-size:15px;color:#4a4a6a;line-height:1.6;">
                              %s
                            </p>
                            <p style="margin:0 0 28px;font-size:15px;color:#4a4a6a;line-height:1.6;">
                              Pour que votre paie puisse être traitée dans les délais,
                              merci de finaliser et de soumettre votre CRA
                              <strong style="color:#1e3a5f;">avant le %s</strong>.
                            </p>

                            <!-- Alerte douce -->
                            <table role="presentation" width="100%%" cellspacing="0" cellpadding="0"
                                   style="margin-bottom:28px;">
                              <tr>
                                <td style="background:#fff8e1;border-left:4px solid #f59e0b;
                                           border-radius:4px;padding:14px 18px;">
                                  <p style="margin:0;font-size:14px;color:#7c5f00;line-height:1.5;">
                                    ⚠️&nbsp; Sans CRA soumis et validé avant la date limite,
                                    le traitement de votre paiement pour <strong>%s</strong>
                                    pourrait être retardé. Nous comptons sur vous !
                                  </p>
                                </td>
                              </tr>
                            </table>

                            <!-- Bouton CTA -->
                            <table role="presentation" cellspacing="0" cellpadding="0"
                                   style="margin:0 auto 32px;">
                              <tr>
                                <td align="center"
                                    style="background:linear-gradient(135deg,#1e3a5f,#2d6a9f);
                                           border-radius:6px;">
                                  <a href="%s"
                                     style="display:inline-block;padding:14px 36px;
                                            font-size:15px;font-weight:600;color:#ffffff;
                                            text-decoration:none;letter-spacing:.3px;">
                                    Saisir mon CRA →
                                  </a>
                                </td>
                              </tr>
                            </table>

                            <p style="margin:0;font-size:14px;color:#888;line-height:1.5;">
                              En cas de difficulté ou de question, n'hésitez pas à contacter
                              votre responsable ou l'équipe administrative.
                            </p>
                          </td>
                        </tr>

                        <!-- Pied de page -->
                        <tr>
                          <td style="background:#f8fafc;padding:20px 40px;
                                     border-top:1px solid #e8ecf0;text-align:center;">
                            <p style="margin:0;font-size:12px;color:#aab;line-height:1.5;">
                              Ce message est envoyé automatiquement — merci de ne pas y répondre.<br>
                              Plateforme multi-agent © %d
                            </p>
                          </td>
                        </tr>

                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(
                monthLabel,
                firstName,
                statusNote,
                deadline,
                monthLabel,
                craUrl,
                java.time.Year.now().getValue()
        );
    }

    private String buildStatusNote(String status, String monthLabel) {
        if (status == null) {
            return "Nous n'avons pas encore reçu votre Compte Rendu d'Activité (CRA) "
                    + "pour le mois de <strong style=\"color:#1e3a5f;\">" + monthLabel + "</strong>. "
                    + "Il semble que vous n'ayez pas encore commencé la saisie.";
        }
        return switch (status) {
            case "BROUILLON" -> "Votre CRA de <strong style=\"color:#1e3a5f;\">" + monthLabel + "</strong> "
                    + "est en cours de saisie mais n'a pas encore été soumis pour validation. "
                    + "Pensez à le finaliser et à cliquer sur <em>Soumettre</em> !";
            case "REFUSE"    -> "Votre CRA de <strong style=\"color:#1e3a5f;\">" + monthLabel + "</strong> "
                    + "a été refusé par votre responsable et nécessite des corrections. "
                    + "Merci de le mettre à jour et de le soumettre à nouveau.";
            default          -> "Votre CRA de <strong style=\"color:#1e3a5f;\">" + monthLabel + "</strong> "
                    + "est en attente de finalisation.";
        };
    }

    private String extractFirstName(String fullName) {
        if (fullName == null || fullName.isBlank()) return "Consultant";
        String[] parts = fullName.trim().split("\\s+");
        String firstName = parts[0];
        // Capitalize first letter
        return firstName.substring(0, 1).toUpperCase(Locale.FRENCH) + firstName.substring(1).toLowerCase(Locale.FRENCH);
    }
}
