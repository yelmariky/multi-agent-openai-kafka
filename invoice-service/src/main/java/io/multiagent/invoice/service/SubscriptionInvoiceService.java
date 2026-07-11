package io.multiagent.invoice.service;

import io.multiagent.invoice.model.SimpleInvoiceRequest;
import io.multiagent.invoice.model.SubscriptionInvoiceRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * Facture d'abonnement SaaS IA-INSIGHT — construit une SimpleInvoiceRequest
 * à partir de la grille tarifaire officielle puis délègue au générateur existant
 * (PDF/Excel, persistance, suivi de paiement inchangés).
 *
 * Stratégie : facturation d'avance (annuelle -20%, trimestrielle ou mensuelle),
 * paiement par virement — mentions légales françaises incluses.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionInvoiceService {

    /** Prix mensuel HT par consultant, par offre. */
    private static final Map<String, BigDecimal> MONTHLY_PRICE_HT = Map.of(
            "ESSENTIEL", new BigDecimal("29"),
            "CROISSANCE", new BigDecimal("49")
    );

    private static final BigDecimal ANNUAL_DISCOUNT = new BigDecimal("0.80"); // -20%

    private static final Map<String, Integer> PERIOD_MONTHS = Map.of(
            "MENSUEL", 1,
            "TRIMESTRIEL", 3,
            "ANNUEL", 12
    );

    private static final String DEFAULT_SELLER = "IA-INSIGHT";

    /** Art. L441-10 C. com. : pénalités et indemnité de recouvrement obligatoires sur facture B2B. */
    private static final String SUBSCRIPTION_LATE_PAYMENT_CLAUSE =
            "Paiement par virement à 30 jours. Pénalités de retard : 3 fois le taux d'intérêt légal, "
            + "exigibles sans rappel, plus indemnité forfaitaire de recouvrement de 40 EUR "
            + "(art. L441-10 C. com.). Pas d'escompte pour paiement anticipé. "
            + "À défaut de paiement après mise en demeure, l'accès au service pourra être suspendu.";

    private static final DateTimeFormatter FR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final InvoiceService invoiceService;

    public InvoiceService.GeneratedInvoiceFiles generate(SubscriptionInvoiceRequest request) throws IOException {
        validate(request);

        String offer = request.offer().trim().toUpperCase(Locale.ROOT);
        String period = request.billingPeriod().trim().toUpperCase(Locale.ROOT);
        int months = PERIOD_MONTHS.get(period);

        BigDecimal monthlyHt = "ENTERPRISE".equals(offer)
                ? request.negotiatedMonthlyPriceHt()
                : MONTHLY_PRICE_HT.get(offer);

        // Prix HT par consultant pour toute la période (remise -20% sur l'annuel prépayé)
        BigDecimal unitPeriodHt = monthlyHt.multiply(BigDecimal.valueOf(months));
        if ("ANNUEL".equals(period)) {
            unitPeriodHt = unitPeriodHt.multiply(ANNUAL_DISCOUNT);
        }
        unitPeriodHt = unitPeriodHt.setScale(2, RoundingMode.HALF_UP);

        LocalDate periodStart = request.periodStart();
        LocalDate periodEnd = periodStart.plusMonths(months).minusDays(1);
        LocalDate invoiceDate = request.invoiceDate() != null ? request.invoiceDate() : LocalDate.now();

        String invoiceName = "ABO-" + periodStart.format(DateTimeFormatter.ofPattern("yyyyMM"))
                + "-" + clientSlug(request.clientCompanyName());

        String title = "Abonnement IA-INSIGHT - Offre " + offerLabel(offer);

        String notes = "Periode d'abonnement : du " + periodStart.format(FR_DATE)
                + " au " + periodEnd.format(FR_DATE) + ". "
                + request.consultantCount() + " consultant(s) x " + unitPeriodHt + " EUR HT ("
                + periodLabel(period) + ("ANNUEL".equals(period) ? ", remise -20% incluse" : "")
                + "). Renouvellement par nouvelle facture a echeance de periode."
                + (request.notes() != null && !request.notes().isBlank() ? " " + request.notes() : "");

        SimpleInvoiceRequest simple = new SimpleInvoiceRequest(
                invoiceName,
                invoiceDate,
                periodStart.format(DateTimeFormatter.ofPattern("yyyy-MM")),
                request.sellerCompanyName() != null && !request.sellerCompanyName().isBlank()
                        ? request.sellerCompanyName() : DEFAULT_SELLER,
                null,                          // adresse émetteur — résolue via le profil vendeur en base
                null,                          // RCS émetteur — idem
                request.clientCompanyName(),
                request.clientAddress(),
                request.clientRcs(),
                title,
                request.consultantCount().doubleValue(),   // Quantité = nombre de consultants
                unitPeriodHt,                              // PU HT = prix par consultant pour la période
                null,                                      // totalHt — recalculé par normalize()
                null,                                      // TVA 20% par défaut
                null,                                      // totalTtc — recalculé
                null,                                      // EUR par défaut
                invoiceDate.plusDays(30),                  // paiement à 30 jours
                SUBSCRIPTION_LATE_PAYMENT_CLAUSE,
                notes,
                null,                                      // pas d'absences sur un abonnement
                null,                                      // pas de consultant émetteur
                null                                       // pas de projet
        );

        log.info("[Abonnement] Facture {} — offre={} consultants={} période={} ({} → {})",
                invoiceName, offer, request.consultantCount(), period, periodStart, periodEnd);
        return invoiceService.generate(simple, "subscription:" + offer + ":" + period);
    }

    /**
     * Facture d'ajustement prorata : consultants ajoutés en cours de période.
     * PU HT = prix mensuel effectif de l'offre × mois restants (mois entamé dû).
     */
    public InvoiceService.GeneratedInvoiceFiles generateSeatAdjustment(
            String clientCompanyName, String clientAddress, String clientRcs,
            String offer, int addedSeats, String billingPeriod,
            BigDecimal negotiatedMonthlyPriceHt,
            LocalDate periodEnd, int remainingMonths) throws IOException {

        String normalizedOffer = offer.trim().toUpperCase(Locale.ROOT);
        BigDecimal monthlyHt = "ENTERPRISE".equals(normalizedOffer)
                ? negotiatedMonthlyPriceHt
                : MONTHLY_PRICE_HT.get(normalizedOffer);
        if (monthlyHt == null) {
            throw new IllegalArgumentException("Offre inconnue ou prix Enterprise manquant : " + offer);
        }
        BigDecimal effectiveMonthly = "ANNUEL".equals(billingPeriod.trim().toUpperCase(Locale.ROOT))
                ? monthlyHt.multiply(ANNUAL_DISCOUNT)
                : monthlyHt;
        BigDecimal unitHt = effectiveMonthly.multiply(BigDecimal.valueOf(remainingMonths))
                .setScale(2, RoundingMode.HALF_UP);

        LocalDate invoiceDate = LocalDate.now();
        String invoiceName = "ABO-ADJ-" + invoiceDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + clientSlug(clientCompanyName);

        SimpleInvoiceRequest simple = new SimpleInvoiceRequest(
                invoiceName,
                invoiceDate,
                invoiceDate.format(DateTimeFormatter.ofPattern("yyyy-MM")),
                DEFAULT_SELLER,
                null, null,
                clientCompanyName, clientAddress, clientRcs,
                "Ajustement abonnement IA-INSIGHT - Offre " + offerLabel(normalizedOffer),
                (double) addedSeats,
                unitHt,
                null, null, null, null,
                invoiceDate.plusDays(30),
                SUBSCRIPTION_LATE_PAYMENT_CLAUSE,
                "Ajout de " + addedSeats + " consultant(s) en cours de periode. Prorata de "
                        + remainingMonths + " mois (jusqu'au " + periodEnd.format(FR_DATE) + ") x "
                        + effectiveMonthly.setScale(2, RoundingMode.HALF_UP) + " EUR HT/mois/consultant.",
                null, null, null
        );

        log.info("[Abonnement] Ajustement {} — {} siège(s) x {} mois pour {}",
                invoiceName, addedSeats, remainingMonths, clientCompanyName);
        return invoiceService.generate(simple, "subscription-adjustment:" + normalizedOffer);
    }

    private void validate(SubscriptionInvoiceRequest r) {
        if (r == null) throw new IllegalArgumentException("Requête vide");
        if (r.clientCompanyName() == null || r.clientCompanyName().isBlank())
            throw new IllegalArgumentException("clientCompanyName est obligatoire");
        if (r.offer() == null) throw new IllegalArgumentException("offer est obligatoire (ESSENTIEL, CROISSANCE, ENTERPRISE)");
        String offer = r.offer().trim().toUpperCase(Locale.ROOT);
        if (!MONTHLY_PRICE_HT.containsKey(offer) && !"ENTERPRISE".equals(offer))
            throw new IllegalArgumentException("Offre inconnue : " + r.offer());
        if ("ENTERPRISE".equals(offer)
                && (r.negotiatedMonthlyPriceHt() == null || r.negotiatedMonthlyPriceHt().signum() <= 0))
            throw new IllegalArgumentException("negotiatedMonthlyPriceHt est obligatoire pour l'offre ENTERPRISE");
        if (r.consultantCount() == null || r.consultantCount() < 1)
            throw new IllegalArgumentException("consultantCount doit être >= 1");
        if (r.billingPeriod() == null || !PERIOD_MONTHS.containsKey(r.billingPeriod().trim().toUpperCase(Locale.ROOT)))
            throw new IllegalArgumentException("billingPeriod invalide (MENSUEL, TRIMESTRIEL, ANNUEL)");
        if (r.periodStart() == null)
            throw new IllegalArgumentException("periodStart est obligatoire");
    }

    private String offerLabel(String offer) {
        return switch (offer) {
            case "ESSENTIEL" -> "Essentiel";
            case "CROISSANCE" -> "Croissance";
            default -> "Enterprise";
        };
    }

    private String periodLabel(String period) {
        return switch (period) {
            case "ANNUEL" -> "facturation annuelle prepayee";
            case "TRIMESTRIEL" -> "facturation trimestrielle prepayee";
            default -> "facturation mensuelle";
        };
    }

    private String clientSlug(String clientName) {
        String slug = clientName.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return slug.length() > 12 ? slug.substring(0, 12) : slug;
    }
}
