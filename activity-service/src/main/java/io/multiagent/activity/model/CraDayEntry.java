package io.multiagent.activity.model;

public record CraDayEntry(
    String date,       // YYYY-MM-DD
    double value,      // 0.0 | 0.5 | 1.0
    String type,       // TRAVAIL | ABSENT | FERIE | WEEKEND
    String projectId   // UUID du projet (nullable — null = hérité du CRA parent)
) {}
