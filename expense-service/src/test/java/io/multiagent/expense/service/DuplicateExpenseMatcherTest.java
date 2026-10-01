package io.multiagent.expense.service;

import io.multiagent.expense.entity.ExpenseEntity;
import io.multiagent.expense.model.ExpenseItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateExpenseMatcherTest {

    private static ExpenseEntity existing(String type, double amount, String description, String status) {
        ExpenseEntity e = new ExpenseEntity();
        e.setType(type);
        e.setAmount(BigDecimal.valueOf(amount));
        e.setDescription(description);
        e.setApprovalStatus(status);
        return e;
    }

    private static ExpenseItem candidate(String type, Double amount, String description) {
        ExpenseItem i = new ExpenseItem();
        i.setType(type);
        i.setAmount(amount);
        i.setDescription(description);
        return i;
    }

    @Test
    void nullInputsAreNotDuplicates() {
        assertThat(DuplicateExpenseMatcher.isDuplicate(null, candidate("hôtel", 100.0, "desc"))).isFalse();
        assertThat(DuplicateExpenseMatcher.isDuplicate(existing("hôtel", 100.0, "desc", "PENDING"), null)).isFalse();
    }

    @Test
    void exactSameExpenseIsDuplicate() {
        ExpenseEntity ex = existing("matériel informatique", 199.06, "Boulanger Grenoble - Saint Martin d'Héres (paiement par Personnel)", "PENDING");
        ExpenseItem cand = candidate("matériel informatique", 199.06, "Boulanger Grenoble - Saint Martin d'Héres (paiement par Personnel)");
        assertThat(DuplicateExpenseMatcher.isDuplicate(ex, cand)).isTrue();
    }

    @Test
    void refusedExpenseNeverBlocksResubmission() {
        ExpenseEntity ex = existing("matériel informatique", 199.06, "Boulanger Grenoble", "REFUSED");
        ExpenseItem cand = candidate("matériel informatique", 199.06, "Boulanger Grenoble");
        assertThat(DuplicateExpenseMatcher.isDuplicate(ex, cand)).isFalse();
    }

    @Test
    void differentTypeIsNotDuplicate() {
        ExpenseEntity ex = existing("restaurant", 30.0, "Le Bistrot", "PENDING");
        ExpenseItem cand = candidate("taxi", 30.0, "Le Bistrot");
        assertThat(DuplicateExpenseMatcher.isDuplicate(ex, cand)).isFalse();
    }

    @Test
    void amountWithinOnePercentToleranceStillDuplicate() {
        ExpenseEntity ex = existing("hôtel", 100.0, "Hotel Ibis Paris", "PENDING");
        ExpenseItem cand = candidate("hôtel", 100.5, "Hotel Ibis Paris"); // +0.5%
        assertThat(DuplicateExpenseMatcher.isDuplicate(ex, cand)).isTrue();
    }

    @Test
    void amountBeyondToleranceIsNotDuplicate() {
        ExpenseEntity ex = existing("hôtel", 100.0, "Hotel Ibis Paris", "PENDING");
        ExpenseItem cand = candidate("hôtel", 150.0, "Hotel Ibis Paris");
        assertThat(DuplicateExpenseMatcher.isDuplicate(ex, cand)).isFalse();
    }

    @Test
    void differentDescriptionIsNotDuplicate() {
        ExpenseEntity ex = existing("restaurant", 30.0, "Le Bistrot Lyonnais", "PENDING");
        ExpenseItem cand = candidate("restaurant", 30.0, "La Brasserie du Port");
        assertThat(DuplicateExpenseMatcher.isDuplicate(ex, cand)).isFalse();
    }

    @Test
    void missingDescriptionOnEitherSideIsNotConclusive() {
        ExpenseEntity ex = existing("restaurant", 30.0, null, "PENDING");
        ExpenseItem cand = candidate("restaurant", 30.0, "Le Bistrot");
        assertThat(DuplicateExpenseMatcher.isDuplicate(ex, cand)).isFalse();
    }
}
