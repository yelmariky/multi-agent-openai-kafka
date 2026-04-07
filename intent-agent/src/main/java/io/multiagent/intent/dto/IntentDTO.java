package io.multiagent.intent.dto;

import lombok.Data;

@Data
public class IntentDTO {

    /**
     * "create_expense", "generate_expense_report", "smalltalk", "unknown", "error"
     */
    private String intent;

    /**
     * Score de confiance entre 0.0 et 1.0
     */
    private double confidence;

    /**
     * Explication courte donnée par l'IA
     */
    private String explanation;

    /**
     * Texte original envoyé par l'utilisateur
     */
    private String originalText;
}