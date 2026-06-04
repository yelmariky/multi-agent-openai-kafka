package io.multiagent.notefrais.expense.service;

import io.multiagent.notefrais.expense.repository.ExpenseWeaviateRepository;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Génère un identifiant incrémental réinitialisé chaque mois (année-mois).
 * Incrémente à partir du max existant dans Weaviate pour éviter les resets après redémarrage.
 */
public class ExpenseIdGenerator {

    private final ConcurrentHashMap<YearMonth, AtomicInteger> counters = new ConcurrentHashMap<>();
    private final ExpenseWeaviateRepository expenseRepo;

    public ExpenseIdGenerator(ExpenseWeaviateRepository expenseRepo) {
        this.expenseRepo = expenseRepo;
    }

    public int nextId(LocalDate date) {
        YearMonth ym = YearMonth.from(date);
        return counters.computeIfAbsent(ym, this::initCounter).incrementAndGet();
    }

    private AtomicInteger initCounter(YearMonth ym) {
        int start = 0;
        try {
            start = expenseRepo.findMaxExpenseId(ym);
        } catch (Exception ignored) {
        }
        return new AtomicInteger(start);
    }
}
