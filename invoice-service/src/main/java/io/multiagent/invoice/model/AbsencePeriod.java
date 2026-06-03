package io.multiagent.invoice.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Période d'absence (congé, RTT…) renseignée par le LLM pour le calcul du nombre de jours facturés.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AbsencePeriod {
    /** Date de début ISO (YYYY-MM-DD) */
    private String from;
    /** Date de fin ISO (YYYY-MM-DD), bornes incluses */
    private String to;
}
