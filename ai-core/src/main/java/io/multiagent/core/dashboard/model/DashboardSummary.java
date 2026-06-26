package io.multiagent.core.dashboard.model;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class DashboardSummary {

    private String month;

    // KPIs globaux
    private double caFacturable;
    private double coutTotal;
    private double margeNette;
    private double tauxMargeGlobal;
    /** Nb de consultants avec daily_cost renseigné (base du calcul de marge) */
    private long   nbConsultantsAvecCout;
    private double tauxActiviteMoyen;
    private int    craEnAttente;
    private int    craValide;
    private double fraisEnAttente;
    private int    fraisEnAttenteCount;
    private int    facturesEnRetard;
    private double montantFacturesEnRetard;

    // Détail par consultant
    private List<ConsultantRow> consultants;

    @Data
    @Builder
    public static class ConsultantRow {
        private String email;
        private String name;
        private String craStatus;
        private String craId;
        private double joursValides;
        private double tjm;
        private double caFacturable;
        private double tauxActivite;
        private int    joursOuvres;
        /** Prix d'achat journalier (null si non renseigné) */
        private Double dailyCost;
        /** Coût total = dailyCost × jours (null si dailyCost non renseigné) */
        private Double coutTotal;
        /** Marge nette = CA - coût (null si dailyCost non renseigné) */
        private Double margeNette;
        /** Taux de marge % (null si dailyCost non renseigné) */
        private Double tauxMarge;
    }
}
