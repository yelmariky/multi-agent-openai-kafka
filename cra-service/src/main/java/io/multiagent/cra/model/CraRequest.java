package io.multiagent.cra.model;

import java.util.List;

public record CraRequest(
    String id,
    String consultant,
    String company,
    String clientCompany,
    String billingMonth,
    List<CraDayEntry> entries,
    double totalDays,
    String status,
    String submittedAt,
    String validatedAt,
    String validatedBy,
    String refusedReason
) {}
