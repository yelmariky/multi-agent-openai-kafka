package io.multiagent.activity.model;

/**
 * Type de véhicule personnel du consultant.
 * Codes language-agnostic — la logique métier compare ces constantes, jamais des chaînes libres.
 */
public enum VehicleType {
    /** Voiture thermique (barème fiscal standard). */
    CAR,
    /** Voiture électrique (barème standard × 1.20). */
    ELECTRIC_CAR,
    /** Moto (barème moto >500cc). */
    MOTORCYCLE,
    /** Pas de véhicule — frais km refusés automatiquement. */
    NONE
}
