package io.multiagent.core.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.multiagent.core.cra.entity.CraEntity;
import io.multiagent.core.cra.repository.CraJpaRepository;
import io.multiagent.core.dashboard.model.DashboardSummary;
import io.multiagent.core.dashboard.repository.InvoiceDashboardRepository;
import io.multiagent.core.expense.repository.ExpenseJpaRepository;
import io.multiagent.core.infrastructure.tenant.TenantContext;
import io.multiagent.core.organization.entity.ConsultantAssignmentEntity;
import io.multiagent.core.organization.repository.ConsultantAssignmentRepository;
import io.multiagent.core.settings.entity.ConsultantProfileEntity;
import io.multiagent.core.settings.repository.ConsultantProfileJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class DashboardService {

    private final ConsultantProfileJpaRepository  consultantProfileRepo;
    private final ConsultantAssignmentRepository  assignmentRepo;
    private final CraJpaRepository                craRepo;
    private final ExpenseJpaRepository            expenseRepo;
    private final InvoiceDashboardRepository      invoiceRepo;
    private final ObjectMapper                    objectMapper;

    public DashboardSummary summary(String month) {
        UUID tenantId = TenantContext.getTenantId();
        YearMonth ym  = YearMonth.parse(month);

        // --- Consultants actifs et facturables (is_consultant=true, hors personnel interne) ---
        List<ConsultantProfileEntity> consultants = consultantProfileRepo.findByTenantIdAndActiveTrue(tenantId)
                .stream()
                .filter(cp -> !Boolean.FALSE.equals(cp.getIsConsultant()))
                .toList();

        int joursOuvres = countWorkingDays(ym);

        List<DashboardSummary.ConsultantRow> rows = new ArrayList<>();
        double totalCa          = 0;
        double totalCout        = 0;
        double totalCaAvecCout  = 0;
        double totalTaux        = 0;
        int    craEnAttente     = 0;
        int    craValide        = 0;
        boolean hasAnyCost      = false;

        for (ConsultantProfileEntity cp : consultants) {
            // CRA du mois
            Optional<CraEntity> craOpt = craRepo.findByTenantIdAndConsultantIgnoreCaseAndBillingMonth(
                    tenantId, cp.getEmail(), month);

            double jours    = 0;
            String status   = "ABSENT";
            String craId    = null;

            if (craOpt.isPresent()) {
                CraEntity cra = craOpt.get();
                status  = cra.getStatus() != null ? cra.getStatus() : "BROUILLON";
                craId   = cra.getId() != null ? cra.getId().toString() : null;
                jours   = cra.getTotalDays() != null ? cra.getTotalDays().doubleValue() : 0;
            }

            // TJM : assignment en priorité, sinon profil (pour affichage indicatif)
            double tjm = resolvedTjm(cp, tenantId);

            // CA : ventilé par projet du CRA (chaque jour au TJM de son projet), sinon jours × TJM
            double ca    = computeCa(cp, craOpt.orElse(null), jours, tenantId, tjm);
            double taux  = joursOuvres > 0 ? Math.round((jours / joursOuvres) * 1000.0) / 10.0 : 0;

            // Marge par consultant (uniquement si daily_cost renseigné)
            Double dailyCost  = cp.getDailyCost() != null ? cp.getDailyCost().doubleValue() : null;
            Double coutTotal  = dailyCost != null ? Math.round(dailyCost * jours * 100.0) / 100.0 : null;
            Double margeNette = coutTotal != null ? Math.round((ca - coutTotal) * 100.0) / 100.0 : null;
            Double tauxMarge  = (margeNette != null && ca > 0) ? Math.round((margeNette / ca) * 1000.0) / 10.0 : null;

            totalCa   += ca;
            totalTaux += taux;
            // N'intégrer dans la marge globale QUE les consultants avec daily_cost renseigné
            // (inclure un coût=0 fausserait la marge par excès)
            if (coutTotal != null) { totalCout += coutTotal; totalCaAvecCout += ca; hasAnyCost = true; }

            if ("SOUMIS".equals(status)) craEnAttente++;
            if ("VALIDE".equals(status)) craValide++;

            rows.add(DashboardSummary.ConsultantRow.builder()
                    .email(cp.getEmail())
                    .name(cp.getName() != null ? cp.getName() : cp.getEmail())
                    .craStatus(status)
                    .craId(craId)
                    .joursValides(jours)
                    .tjm(tjm)
                    .caFacturable(ca)
                    .tauxActivite(taux)
                    .joursOuvres(joursOuvres)
                    .dailyCost(dailyCost)
                    .coutTotal(coutTotal)
                    .margeNette(margeNette)
                    .tauxMarge(tauxMarge)
                    .build());
        }

        double tauxMoyen = consultants.isEmpty() ? 0 : Math.round((totalTaux / consultants.size()) * 10.0) / 10.0;

        // --- Frais en attente ---
        var expensesPending = expenseRepo.findByTenantIdAndApprovalStatus(tenantId, "PENDING");
        double fraisTotal = expensesPending.stream()
                .mapToDouble(e -> e.getAmount() != null ? e.getAmount().doubleValue() : 0)
                .sum();

        // --- Factures en retard ---
        var overdueInvoices = invoiceRepo.findOverdue(tenantId, LocalDate.now());
        double montantRetard = overdueInvoices.stream()
                .mapToDouble(i -> i.getTotalTtc() != null ? i.getTotalTtc().doubleValue() : 0)
                .sum();

        double margeGlobale    = hasAnyCost ? Math.round((totalCaAvecCout - totalCout) * 100.0) / 100.0 : 0;
        double tauxMargeGlobal = hasAnyCost && totalCaAvecCout > 0 ? Math.round((margeGlobale / totalCaAvecCout) * 1000.0) / 10.0 : 0;
        long   nbAvecCout      = rows.stream().filter(r -> r.getDailyCost() != null).count();

        return DashboardSummary.builder()
                .month(month)
                .caFacturable(Math.round(totalCa * 100.0) / 100.0)
                .coutTotal(hasAnyCost ? Math.round(totalCout * 100.0) / 100.0 : 0)
                .margeNette(margeGlobale)
                .tauxMargeGlobal(tauxMargeGlobal)
                .nbConsultantsAvecCout(nbAvecCout)
                .tauxActiviteMoyen(tauxMoyen)
                .craEnAttente(craEnAttente)
                .craValide(craValide)
                .fraisEnAttente(Math.round(fraisTotal * 100.0) / 100.0)
                .fraisEnAttenteCount(expensesPending.size())
                .facturesEnRetard(overdueInvoices.size())
                .montantFacturesEnRetard(Math.round(montantRetard * 100.0) / 100.0)
                .consultants(rows)
                .build();
    }

    private double resolvedTjm(ConsultantProfileEntity cp, UUID tenantId) {
        // 1. TJM du premier assignment actif
        var assignments = assignmentRepo.findByConsultantProfileIdAndTenantId(cp.getId(), tenantId);
        if (!assignments.isEmpty() && assignments.get(0).getTjm() != null) {
            return assignments.get(0).getTjm().doubleValue();
        }
        // 2. TJM du profil
        if (cp.getTjm() != null) return cp.getTjm().doubleValue();
        return 0;
    }

    /**
     * CA facturable = somme, pour chaque jour travaillé du CRA, de (valeur × TJM du projet de ce jour).
     * Un consultant peut travailler sur plusieurs projets/clients à TJM différents dans le même mois.
     * Repli sur jours × fallbackTjm si le CRA n'a pas d'entrées exploitables.
     */
    private double computeCa(ConsultantProfileEntity cp, CraEntity cra, double totalDays,
                             UUID tenantId, double fallbackTjm) {
        // Table projet → TJM depuis les affectations du consultant
        Map<String, Double> tjmByProject = new HashMap<>();
        for (ConsultantAssignmentEntity a : assignmentRepo.findByConsultantProfileIdAndTenantId(cp.getId(), tenantId)) {
            if (a.getProject() != null && a.getProject().getId() != null && a.getTjm() != null) {
                tjmByProject.put(a.getProject().getId().toString(), a.getTjm().doubleValue());
            }
        }

        if (cra == null || cra.getEntriesJson() == null || cra.getEntriesJson().isBlank() || tjmByProject.isEmpty()) {
            return Math.round(totalDays * fallbackTjm * 100.0) / 100.0;
        }

        try {
            JsonNode entries = objectMapper.readTree(cra.getEntriesJson());
            double ca = 0, matchedDays = 0;
            for (JsonNode e : entries) {
                if (!"TRAVAIL".equals(e.path("type").asText())) continue;
                double value = e.path("value").asDouble(0);
                String projectId = e.hasNonNull("projectId") ? e.get("projectId").asText() : null;
                Double projectTjm = projectId != null ? tjmByProject.get(projectId) : null;
                ca += value * (projectTjm != null ? projectTjm : fallbackTjm);
                matchedDays += value;
            }
            // Jours non couverts par les entrées (défensif) : au TJM de repli
            if (matchedDays < totalDays) ca += (totalDays - matchedDays) * fallbackTjm;
            return Math.round(ca * 100.0) / 100.0;
        } catch (Exception ex) {
            log.warn("[Dashboard] CRA entries illisibles pour {} — repli jours×TJM : {}", cp.getEmail(), ex.getMessage());
            return Math.round(totalDays * fallbackTjm * 100.0) / 100.0;
        }
    }

    /** Jours ouvrés du mois = lundi-vendredi hors jours fériés fixes français. */
    private int countWorkingDays(YearMonth ym) {
        Set<MonthDay> holidays = frenchFixedHolidays();
        int count = 0;
        LocalDate d = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        while (!d.isAfter(end)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.contains(MonthDay.from(d))) {
                count++;
            }
            d = d.plusDays(1);
        }
        return count;
    }

    /** Jours fériés fixes français (mêmes que le calcul frais km / factures — cohérence système). */
    private Set<MonthDay> frenchFixedHolidays() {
        Set<MonthDay> h = new HashSet<>();
        h.add(MonthDay.of(1, 1));    // Jour de l'an
        h.add(MonthDay.of(5, 1));    // Fête du travail
        h.add(MonthDay.of(5, 8));    // Victoire 1945
        h.add(MonthDay.of(7, 14));   // Fête nationale
        h.add(MonthDay.of(8, 15));   // Assomption
        h.add(MonthDay.of(11, 1));   // Toussaint
        h.add(MonthDay.of(11, 11));  // Armistice
        h.add(MonthDay.of(12, 25));  // Noël
        return h;
    }
}
