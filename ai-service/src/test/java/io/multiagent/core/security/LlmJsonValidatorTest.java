package io.multiagent.core.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LlmJsonValidator — validation schéma JSON des réponses LLM")
class LlmJsonValidatorTest {

    // ── validateExpense ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("validateExpense()")
    class ValidateExpense {

        @Test @DisplayName("JSON valide complet → ok")
        void validJson_returnsOk() {
            var r = LlmJsonValidator.validateExpense(
                "{\"type\":\"restaurant\",\"amount\":45.0,\"date\":\"2026-06-25\",\"km\":null}");
            assertThat(r.valid()).isTrue();
        }

        @Test @DisplayName("null → fail")
        void nullJson_returnsFail() {
            assertThat(LlmJsonValidator.validateExpense(null).valid()).isFalse();
        }

        @Test @DisplayName("vide → fail")
        void blankJson_returnsFail() {
            assertThat(LlmJsonValidator.validateExpense("   ").valid()).isFalse();
        }

        @Test @DisplayName("JSON malformé → fail")
        void malformedJson_returnsFail() {
            var r = LlmJsonValidator.validateExpense("{not-valid-json}");
            assertThat(r.valid()).isFalse();
            assertThat(r.reason()).contains("malformé");
        }

        @Test @DisplayName("tableau JSON → fail (objet attendu)")
        void jsonArray_returnsFail() {
            var r = LlmJsonValidator.validateExpense("[{\"type\":\"restaurant\"}]");
            assertThat(r.valid()).isFalse();
            assertThat(r.reason()).contains("objet JSON");
        }

        @Test @DisplayName("amount non-numérique → fail")
        void amountNotNumber_returnsFail() {
            var r = LlmJsonValidator.validateExpense("{\"amount\":\"quarante-cinq\"}");
            assertThat(r.valid()).isFalse();
            assertThat(r.reason()).contains("amount");
        }

        @Test @DisplayName("amount négatif → fail")
        void negativeAmount_returnsFail() {
            var r = LlmJsonValidator.validateExpense("{\"amount\":-10.0}");
            assertThat(r.valid()).isFalse();
            assertThat(r.reason()).contains("négatif");
        }

        @Test @DisplayName("amount null → ok (champ optionnel)")
        void nullAmount_returnsOk() {
            assertThat(LlmJsonValidator.validateExpense("{\"amount\":null}").valid()).isTrue();
        }

        @Test @DisplayName("amount = 0 → ok")
        void zeroAmount_returnsOk() {
            assertThat(LlmJsonValidator.validateExpense("{\"amount\":0}").valid()).isTrue();
        }

        @Test @DisplayName("km non-numérique → fail")
        void kmNotNumber_returnsFail() {
            var r = LlmJsonValidator.validateExpense("{\"km\":\"beaucoup\"}");
            assertThat(r.valid()).isFalse();
            assertThat(r.reason()).contains("km");
        }

        @Test @DisplayName("km négatif → fail")
        void negativeKm_returnsFail() {
            var r = LlmJsonValidator.validateExpense("{\"km\":-5}");
            assertThat(r.valid()).isFalse();
            assertThat(r.reason()).contains("km");
        }

        @Test @DisplayName("km = 0 → ok")
        void zeroKm_returnsOk() {
            assertThat(LlmJsonValidator.validateExpense("{\"km\":0}").valid()).isTrue();
        }

        @Test @DisplayName("km > 2000 → ok mais warning (log only)")
        void excessiveKm_returnsOkWithWarning() {
            // km excessif = warning log seulement, pas bloquant
            assertThat(LlmJsonValidator.validateExpense("{\"km\":2500}").valid()).isTrue();
        }

        @Test @DisplayName("date format invalide → ok (non bloquant, warning)")
        void invalidDateFormat_returnsOk() {
            // date invalide = warning, non bloquant
            assertThat(LlmJsonValidator.validateExpense("{\"date\":\"25/06/2026\"}").valid()).isTrue();
        }

        @Test @DisplayName("date ISO valide → ok")
        void validDateISO_returnsOk() {
            assertThat(LlmJsonValidator.validateExpense("{\"date\":\"2026-06-25\"}").valid()).isTrue();
        }

        @Test @DisplayName("type inconnu → ok (warning non bloquant)")
        void unknownType_returnsOk() {
            assertThat(LlmJsonValidator.validateExpense("{\"type\":\"voiture-volante\"}").valid()).isTrue();
        }

        @Test @DisplayName("type null → ok")
        void nullType_returnsOk() {
            assertThat(LlmJsonValidator.validateExpense("{\"type\":null}").valid()).isTrue();
        }

        @Test @DisplayName("type restaurant (valide) → ok")
        void knownType_returnsOk() {
            assertThat(LlmJsonValidator.validateExpense("{\"type\":\"restaurant\",\"amount\":45.0}").valid()).isTrue();
        }

        @Test @DisplayName("type frais_km (valide) → ok")
        void knownTypeKm_returnsOk() {
            assertThat(LlmJsonValidator.validateExpense("{\"type\":\"frais_km\",\"km\":88.0}").valid()).isTrue();
        }

        @Test @DisplayName("objet vide → ok (tous champs optionnels)")
        void emptyObject_returnsOk() {
            assertThat(LlmJsonValidator.validateExpense("{}").valid()).isTrue();
        }
    }

    // ── validateIntent ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("validateIntent()")
    class ValidateIntent {

        @Test @DisplayName("intent valide complet → ok")
        void validIntent_returnsOk() {
            var r = LlmJsonValidator.validateIntent(
                "{\"intent\":\"create_expense\",\"confidence\":0.95}");
            assertThat(r.valid()).isTrue();
        }

        @Test @DisplayName("null → fail")
        void nullJson_returnsFail() {
            assertThat(LlmJsonValidator.validateIntent(null).valid()).isFalse();
        }

        @Test @DisplayName("vide → fail")
        void blankJson_returnsFail() {
            assertThat(LlmJsonValidator.validateIntent("").valid()).isFalse();
        }

        @Test @DisplayName("JSON malformé → fail")
        void malformedJson_returnsFail() {
            assertThat(LlmJsonValidator.validateIntent("not-json").valid()).isFalse();
        }

        @Test @DisplayName("tableau → fail (objet attendu)")
        void jsonArray_returnsFail() {
            assertThat(LlmJsonValidator.validateIntent("[\"intent\"]").valid()).isFalse();
        }

        @Test @DisplayName("intent manquant → fail")
        void missingIntent_returnsFail() {
            var r = LlmJsonValidator.validateIntent("{\"confidence\":0.9}");
            assertThat(r.valid()).isFalse();
            assertThat(r.reason()).contains("intent");
        }

        @Test @DisplayName("confidence manquant → fail")
        void missingConfidence_returnsFail() {
            var r = LlmJsonValidator.validateIntent("{\"intent\":\"create_expense\"}");
            assertThat(r.valid()).isFalse();
            assertThat(r.reason()).contains("confidence");
        }

        @Test @DisplayName("confidence > 1 → fail")
        void confidenceAboveOne_returnsFail() {
            var r = LlmJsonValidator.validateIntent(
                "{\"intent\":\"create_expense\",\"confidence\":1.5}");
            assertThat(r.valid()).isFalse();
            assertThat(r.reason()).contains("confidence");
        }

        @Test @DisplayName("confidence < 0 → fail")
        void confidenceBelowZero_returnsFail() {
            var r = LlmJsonValidator.validateIntent(
                "{\"intent\":\"error\",\"confidence\":-0.1}");
            assertThat(r.valid()).isFalse();
        }

        @Test @DisplayName("confidence = 0 → ok")
        void confidenceZero_returnsOk() {
            assertThat(LlmJsonValidator.validateIntent(
                "{\"intent\":\"error\",\"confidence\":0.0}").valid()).isTrue();
        }

        @Test @DisplayName("confidence = 1 → ok")
        void confidenceOne_returnsOk() {
            assertThat(LlmJsonValidator.validateIntent(
                "{\"intent\":\"create_expense\",\"confidence\":1.0}").valid()).isTrue();
        }
    }

    // ── ValidationResult ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("ValidationResult")
    class ValidationResultTest {

        @Test @DisplayName("ok() → valid=true, reason=null")
        void okResult() {
            var r = LlmJsonValidator.ValidationResult.ok();
            assertThat(r.valid()).isTrue();
            assertThat(r.reason()).isNull();
        }

        @Test @DisplayName("fail(msg) → valid=false, reason=msg")
        void failResult() {
            var r = LlmJsonValidator.ValidationResult.fail("test error");
            assertThat(r.valid()).isFalse();
            assertThat(r.reason()).isEqualTo("test error");
        }
    }
}
