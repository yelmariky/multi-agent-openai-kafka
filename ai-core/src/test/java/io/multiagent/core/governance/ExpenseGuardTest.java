package io.multiagent.core.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ExpenseGuard business guardrails.
 * No Spring context needed — pure logic tests.
 */
class ExpenseGuardTest {

    private ExpenseGuard guard;

    @BeforeEach
    void setUp() {
        guard = new ExpenseGuard();
    }

    // --- AUTO_APPROVABLE ---

    @Test
    void cleanExpense_shouldBeAutoApprovable() {
        ExpenseGuard.GuardResult result = guard.evaluate(
                new BigDecimal("100.00"), "REPAS", LocalDate.now().minusDays(1), null, 0.95);

        assertThat(result.decision()).isEqualTo(ExpenseGuard.ReviewDecision.AUTO_APPROVABLE);
        assertThat(result.flags()).isEmpty();
        assertThat(result.requiresHumanReview()).isFalse();
    }

    // --- MONTANT_ELEVE ---

    @Test
    void highAmount_shouldFlag_MONTANT_ELEVE() {
        ExpenseGuard.GuardResult result = guard.evaluate(
                new BigDecimal("501.00"), "REPAS", LocalDate.now().minusDays(1), null, 0.95);

        assertThat(result.flags()).contains("MONTANT_ELEVE");
        assertThat(result.requiresHumanReview()).isTrue();
    }

    @Test
    void exactThresholdAmount_shouldNotFlag() {
        ExpenseGuard.GuardResult result = guard.evaluate(
                new BigDecimal("500.00"), "REPAS", LocalDate.now().minusDays(1), null, 0.95);

        assertThat(result.flags()).doesNotContain("MONTANT_ELEVE");
    }

    // --- DATE_FUTURE ---

    @Test
    void futureDate_shouldFlag_DATE_FUTURE() {
        ExpenseGuard.GuardResult result = guard.evaluate(
                new BigDecimal("100.00"), "REPAS", LocalDate.now().plusDays(1), null, 0.95);

        assertThat(result.flags()).contains("DATE_FUTURE");
        assertThat(result.requiresHumanReview()).isTrue();
    }

    @Test
    void todayDate_shouldNotFlag() {
        ExpenseGuard.GuardResult result = guard.evaluate(
                new BigDecimal("100.00"), "REPAS", LocalDate.now(), null, 0.95);

        assertThat(result.flags()).doesNotContain("DATE_FUTURE");
    }

    // --- TYPE_INCONNU ---

    @Test
    void unknownType_shouldFlag_TYPE_INCONNU() {
        ExpenseGuard.GuardResult result = guard.evaluate(
                new BigDecimal("100.00"), "CADEAU", LocalDate.now().minusDays(1), null, 0.95);

        assertThat(result.flags()).contains("TYPE_INCONNU");
        assertThat(result.requiresHumanReview()).isTrue();
    }

    @Test
    void knownTypes_shouldNotFlag() {
        for (String type : new String[]{"REPAS", "TRANSPORT", "HEBERGEMENT", "MATERIEL",
                "TELEPHONE", "FORMATION", "KM", "ABSENCE"}) {
            ExpenseGuard.GuardResult result = guard.evaluate(
                    new BigDecimal("100.00"), type, LocalDate.now().minusDays(1), null, 0.95);
            assertThat(result.flags())
                    .as("Type %s should not trigger TYPE_INCONNU", type)
                    .doesNotContain("TYPE_INCONNU");
        }
    }

    // --- KM_ELEVE ---

    @Test
    void highKm_shouldFlag_KM_ELEVE() {
        ExpenseGuard.GuardResult result = guard.evaluate(
                new BigDecimal("250.00"), "KM", LocalDate.now().minusDays(1),
                new BigDecimal("501.0"), 0.95);

        assertThat(result.flags()).contains("KM_ELEVE");
        assertThat(result.requiresHumanReview()).isTrue();
    }

    // --- CONFIANCE_FAIBLE ---

    @Test
    void lowConfidence_shouldFlag_CONFIANCE_FAIBLE() {
        ExpenseGuard.GuardResult result = guard.evaluate(
                new BigDecimal("100.00"), "REPAS", LocalDate.now().minusDays(1), null, 0.60);

        assertThat(result.flags()).contains("CONFIANCE_FAIBLE");
        assertThat(result.requiresHumanReview()).isTrue();
    }

    @Test
    void borderlineConfidence_exactThreshold_shouldNotFlag() {
        ExpenseGuard.GuardResult result = guard.evaluate(
                new BigDecimal("100.00"), "REPAS", LocalDate.now().minusDays(1), null, 0.70);

        assertThat(result.flags()).doesNotContain("CONFIANCE_FAIBLE");
    }

    // --- MONTANT_ABSENT ---

    @Test
    void nullAmount_shouldFlag_MONTANT_ABSENT() {
        ExpenseGuard.GuardResult result = guard.evaluate(
                null, "REPAS", LocalDate.now().minusDays(1), null, 0.95);

        assertThat(result.flags()).contains("MONTANT_ABSENT");
        assertThat(result.requiresHumanReview()).isTrue();
    }

    // --- Multiple flags ---

    @Test
    void multipleProblems_shouldAccumulateAllFlags() {
        ExpenseGuard.GuardResult result = guard.evaluate(
                new BigDecimal("999.00"), "INCONNU", LocalDate.now().plusDays(5), null, 0.50);

        assertThat(result.flags()).containsExactlyInAnyOrder(
                "MONTANT_ELEVE", "DATE_FUTURE", "TYPE_INCONNU", "CONFIANCE_FAIBLE");
        assertThat(result.requiresHumanReview()).isTrue();
    }

    // --- flagsAsJson ---

    @Test
    void flagsAsJson_shouldReturnEmptyObject_whenNoFlags() {
        ExpenseGuard.GuardResult result = guard.evaluate(
                new BigDecimal("100.00"), "REPAS", LocalDate.now().minusDays(1), null, 0.95);

        assertThat(result.flagsAsJson()).isEqualTo("{}");
    }

    @Test
    void flagsAsJson_shouldContainAllFlags() {
        ExpenseGuard.GuardResult result = guard.evaluate(
                null, "BAD_TYPE", LocalDate.now().plusDays(1), null, 0.50);

        String json = result.flagsAsJson();
        assertThat(json).contains("\"MONTANT_ABSENT\":true");
        assertThat(json).contains("\"TYPE_INCONNU\":true");
        assertThat(json).contains("\"DATE_FUTURE\":true");
        assertThat(json).contains("\"CONFIANCE_FAIBLE\":true");
        assertThat(json).startsWith("{").endsWith("}");
    }

    // --- Null-safety ---

    @Test
    void nullType_shouldNotThrow() {
        ExpenseGuard.GuardResult result = guard.evaluate(
                new BigDecimal("100.00"), null, LocalDate.now().minusDays(1), null, 0.95);

        assertThat(result).isNotNull();
        assertThat(result.flags()).doesNotContain("TYPE_INCONNU");
    }

    @Test
    void nullDate_shouldNotThrow() {
        ExpenseGuard.GuardResult result = guard.evaluate(
                new BigDecimal("100.00"), "REPAS", null, null, 0.95);

        assertThat(result).isNotNull();
        assertThat(result.flags()).doesNotContain("DATE_FUTURE");
    }
}
