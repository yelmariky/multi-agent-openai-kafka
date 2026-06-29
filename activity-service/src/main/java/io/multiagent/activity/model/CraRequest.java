package io.multiagent.activity.model;

import java.util.List;

public record CraRequest(
    String id,                   // UUID (null for new)
    String consultant,           // Email du consultant
    String company,              // Société émettrice (IA-INSIGHT)
    String clientCompany,        // Société cliente
    String clientContactEmail,   // Email du contact chez le client (nullable)
    String billingMonth,         // YYYY-MM
    List<CraDayEntry> entries,
    double totalDays,            // calculé
    String status,               // BROUILLON | SOUMIS | VALIDE | REFUSE
    String submittedAt,          // ISO datetime string
    String validatedAt,          // ISO datetime string
    String validatedBy,          // Nom du validateur
    String refusedReason,        // Motif de refus (retour en BROUILLON)
    String missionId,            // UUID de la mission liée (nullable)
    String projectId,            // UUID du projet associé (nullable)
    String clientValidationRef,  // Référence retour client (BC, email…)
    String clientValidationDate  // Date validation client ISO (YYYY-MM-DD)
) {}
