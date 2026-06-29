package io.multiagent.core.expense;

import io.multiagent.core.expense.service.RAGService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires sur les méthodes privées de RAGService via réflexion.
 * Couvre la logique métier km sans nécessiter un contexte Spring complet.
 */
@DisplayName("RAGService — extraction km et barème")
class RAGServiceKmTest {

    @Nested
    @DisplayName("tryExtractKmByRules — extraction regex")
    class TryExtractKmByRules {

        private Object invokeExtract(String text) throws Exception {
            // On instancie RAGService via Mockito.spy si possible, sinon on teste la logique
            // directement en réinstanciant un objet avec toutes les deps mockées à null.
            // Pattern: utilisation de la réflexion pour accéder à la méthode privée.
            RAGService svc = org.mockito.Mockito.mock(RAGService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
            ReflectionTestUtils.setField(svc, "kmRate",  0.661);
            ReflectionTestUtils.setField(svc, "kmAnnual", 4999);
            Method m = RAGService.class.getDeclaredMethod("tryExtractKmByRules", String.class);
            m.setAccessible(true);
            return m.invoke(svc, text);
        }

        @Test
        @DisplayName("44 km aller-retour → km=88, monthly=true (par jour)")
        void allerRetour_par_jour() throws Exception {
            Object item = invokeExtract("Frais kilométriques juin, 44 km aller-retour domicile-bureau par jour");
            assertThat(item).isNotNull();
            assertThat(item).hasFieldOrPropertyWithValue("km", 88.0);
            assertThat(item).hasFieldOrPropertyWithValue("monthly", true);
            assertThat(item).hasFieldOrPropertyWithValue("type", "frais_km");
        }

        @Test
        @DisplayName("50 km sans aller-retour → km=50")
        void simple_km() throws Exception {
            Object item = invokeExtract("50 km trajet bureau juin");
            assertThat(item).hasFieldOrPropertyWithValue("km", 50.0);
        }

        @Test
        @DisplayName("km sans chiffre → null (non détecté)")
        void no_number_returns_null() throws Exception {
            Object item = invokeExtract("frais kilométriques sans distance");
            assertThat(item).isNull();
        }

        @Test
        @DisplayName("texte sans km → null")
        void no_km_keyword_returns_null() throws Exception {
            Object item = invokeExtract("restaurant sushi 35€ Paris");
            assertThat(item).isNull();
        }

        @Test
        @DisplayName("mois juin extrait correctement")
        void month_juin_extracted() throws Exception {
            Object item = invokeExtract("44 km par jour juin");
            assertThat(item).isNotNull();
            // date doit être 2026-06-01
            Object date = org.springframework.test.util.ReflectionTestUtils.getField(item, "date");
            assertThat(date.toString()).startsWith("2026-06");
        }

        @Test
        @DisplayName("mois juillet extrait correctement")
        void month_juillet_extracted() throws Exception {
            Object item = invokeExtract("30 km juillet chaque jour");
            Object date = org.springframework.test.util.ReflectionTestUtils.getField(item, "date");
            assertThat(date.toString()).startsWith("2026-07");
        }

        @Test
        @DisplayName("/jour détecté comme mensuel")
        void slash_jour_detected_as_monthly() throws Exception {
            Object item = invokeExtract("40 km/jour domicile-bureau");
            assertThat(item).hasFieldOrPropertyWithValue("monthly", true);
        }

        @Test
        @DisplayName("null input → null output")
        void null_input() throws Exception {
            Object item = invokeExtract(null);
            assertThat(item).isNull();
        }
    }

    @Nested
    @DisplayName("Barème km — computeVoitureCostPerKm")
    class Bareme {

        private double invokeBareme(int kmAnnual) throws Exception {
            RAGService svc = org.mockito.Mockito.mock(RAGService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
            ReflectionTestUtils.setField(svc, "kmRate",  0.661);
            ReflectionTestUtils.setField(svc, "kmAnnual", 4999);
            Method m = RAGService.class.getDeclaredMethod("computeVoitureCostPerKm", int.class);
            m.setAccessible(true);
            return (double) m.invoke(svc, kmAnnual);
        }

        @Test @DisplayName("kmAnnual=3000 → palier 1 (d×0.697)/d = 0.697")
        void palier1() throws Exception {
            assertThat(invokeBareme(3000)).isEqualTo(0.697);
        }

        @Test @DisplayName("kmAnnual=11000 → palier 2 formule (d×0.394+1515)/d")
        void palier2() throws Exception {
            double expected = (11000 * 0.394 + 1515.0) / 11000;
            assertThat(invokeBareme(11000)).isCloseTo(expected, org.assertj.core.data.Offset.offset(0.0001));
        }

        @Test @DisplayName("kmAnnual=25000 → palier 3 (d×0.470)/d = 0.470")
        void palier3() throws Exception {
            assertThat(invokeBareme(25000)).isEqualTo(0.470);
        }

        @Test @DisplayName("kmAnnual=0 → taux par défaut kmRate=0.661")
        void kmAnnualZero_defaultRate() throws Exception {
            assertThat(invokeBareme(0)).isEqualTo(0.661);
        }
    }
}
