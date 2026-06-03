package io.multiagent.core.model;

import java.util.List;

public record CraRequest(
    String id,            // Weaviate UUID (null for new)
    String consultant,    // Nom du consultant
    String company,       // Société émettrice (IA-INSIGHT)
    String clientCompany, // Société cliente
    String billingMonth,  // YYYY-MM
    List<CraDayEntry> entries,
    double totalDays,     // calculé
    String status,        // BROUILLON | SOUMIS | VALIDE | REFUSE
    String submittedAt,   // ISO datetime string
    String validatedAt,   // ISO datetime string
    String validatedBy,   // Nom du validateur
    String refusedReason  // Motif de refus (retour en BROUILLON)
) {}
