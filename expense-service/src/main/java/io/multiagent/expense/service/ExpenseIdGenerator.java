package io.multiagent.expense.service;

import io.multiagent.expense.service.ExpenseDataService;
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
    private final ExpenseDataService expenseDataService;

    public ExpenseIdGenerator(ExpenseDataService expenseDataService) {
        this.expenseDataService = expenseDataService;
    }

    public int nextId(LocalDate date) {
        YearMonth ym = YearMonth.from(date);
        return counters.computeIfAbsent(ym, this::initCounter).incrementAndGet();
    }

    private AtomicInteger initCounter(YearMonth ym) {
        int start = 0;
        try {
            start = expenseDataService.findMaxExpenseId(ym);
        } catch (Exception ignored) {
        }
        return new AtomicInteger(start);
    }
}
