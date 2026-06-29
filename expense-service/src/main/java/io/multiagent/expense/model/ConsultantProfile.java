package io.multiagent.expense.model;

public record ConsultantProfile(
        String id,
        String email,
        String name,
        String role,
        String company,
        String clientName,
        String clientAddress,
        String clientRcs,
        String clientContactEmail,
        Double tjm,
        Boolean active,
        VehicleType vehicleType,
        Integer fiscalPower,
        Integer kmAnnual,
        /** false pour admin/manager (personnel interne non facturable) */
        Boolean isConsultant,
        /** Prix d'achat journalier (€/j) : coût complet salarié ou taux freelance convenu */
        Double dailyCost
) {
    /** Dérive isConsultant depuis le rôle si non fourni explicitement. */
    public boolean billable() {
        if (isConsultant != null) return isConsultant;
        if (role == null) return true;
        String r = role.toLowerCase();
        return !r.equals("admin") && !r.equals("manager") && !r.equals("gestionnaire");
    }
}
