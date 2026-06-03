package io.multiagent.core.model;

public record CraDayEntry(
    String date,   // YYYY-MM-DD
    double value,  // 0.0 | 0.5 | 1.0
    String type    // TRAVAIL | ABSENT | FERIE | WEEKEND
) {}
