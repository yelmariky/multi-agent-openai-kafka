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
            ReflectionTestUtils.setField(svc, "messages", io.multiagent.core.i18n.MessagesFixture.standard());
            ReflectionTestUtils.setField(svc, "keywords", io.multiagent.core.config.TextKeywordsFixture.fromConfigMap());
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
        @DisplayName("texte anglais : round trip + daily + june")
        void english_round_trip_daily() throws Exception {
            Object item = invokeExtract("44 km round trip home-office daily june");
            assertThat(item).hasFieldOrPropertyWithValue("km", 88.0);
            assertThat(item).hasFieldOrPropertyWithValue("monthly", true);
            Object date = ReflectionTestUtils.getField(item, "date");
            assertThat(date.toString()).endsWith("-06-01");
        }

        @Test
        @DisplayName("null input → null output")
        void null_input() throws Exception {
            Object item = invokeExtract(null);
            assertThat(item).isNull();
        }
    }

    @Nested
    @DisplayName("Barème km voiture — toutes tranches de puissance fiscale (BOI-BAREME-000001)")
    class BaremeVoiture {

        private double invokeBareme(int fiscalPower, int kmAnnual) throws Exception {
            RAGService svc = org.mockito.Mockito.mock(RAGService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
            ReflectionTestUtils.setField(svc, "kmRate",  0.661);
            ReflectionTestUtils.setField(svc, "kmAnnual", 4999);
            ReflectionTestUtils.setField(svc, "messages", io.multiagent.core.i18n.MessagesFixture.standard());
            Method m = RAGService.class.getDeclaredMethod("computeVoitureCostPerKm", int.class, int.class);
            m.setAccessible(true);
            return (double) m.invoke(svc, fiscalPower, kmAnnual);
        }

        @Test @DisplayName("7 CV, 3000 km → palier 1 = 0.697 (non-régression)")
        void septCv_palier1() throws Exception {
            assertThat(invokeBareme(7, 3000)).isEqualTo(0.697);
        }

        @Test @DisplayName("7 CV, 11000 km → palier 2 (d×0.394+1515)/d")
        void septCv_palier2() throws Exception {
            double expected = (11000 * 0.394 + 1515.0) / 11000;
            assertThat(invokeBareme(7, 11000)).isCloseTo(expected, org.assertj.core.data.Offset.offset(0.0001));
        }

        @Test @DisplayName("7 CV, 25000 km → palier 3 = 0.470")
        void septCv_palier3() throws Exception {
            assertThat(invokeBareme(7, 25000)).isEqualTo(0.470);
        }

        @Test @DisplayName("kmAnnual=0 → taux par défaut kmRate=0.661")
        void kmAnnualZero_defaultRate() throws Exception {
            assertThat(invokeBareme(7, 0)).isEqualTo(0.661);
        }

        // Le bug corrigé : avant ce fix, TOUTE puissance fiscale utilisait le taux 7CV — ces tests
        // verrouillent que chaque tranche a maintenant son propre coefficient.
        @Test @DisplayName("3 CV et moins, 3000 km → 0.529 (pas 0.697)")
        void troisCv_palier1() throws Exception {
            assertThat(invokeBareme(3, 3000)).isEqualTo(0.529);
            assertThat(invokeBareme(1, 3000)).isEqualTo(0.529);
        }

        @Test @DisplayName("4 CV, 3000 km → 0.606")
        void quatreCv_palier1() throws Exception {
            assertThat(invokeBareme(4, 3000)).isEqualTo(0.606);
        }

        @Test @DisplayName("5 CV, 3000 km → 0.636 (vérifié BOFiP officiel)")
        void cinqCv_palier1() throws Exception {
            assertThat(invokeBareme(5, 3000)).isEqualTo(0.636);
        }

        @Test @DisplayName("5 CV, 8000 km → (d×0.357)+1395 — constante officielle vérifiée via BOFiP")
        void cinqCv_palier2() throws Exception {
            double expected = (8000 * 0.357 + 1395.0) / 8000;
            assertThat(invokeBareme(5, 8000)).isCloseTo(expected, org.assertj.core.data.Offset.offset(0.0001));
        }

        @Test @DisplayName("6 CV, 3000 km → 0.665")
        void sixCv_palier1() throws Exception {
            assertThat(invokeBareme(6, 3000)).isEqualTo(0.665);
        }

        @Test @DisplayName("8 CV → même tranche que 7 CV et plus (barème officiel ne distingue pas au-delà)")
        void huitCv_memeTrancheQue7() throws Exception {
            assertThat(invokeBareme(8, 3000)).isEqualTo(invokeBareme(7, 3000));
        }
    }

    @Nested
    @DisplayName("Barème km moto — toutes tranches de puissance fiscale")
    class BaremeMoto {

        private double invokeBareme(int fiscalPower, int kmAnnual) throws Exception {
            RAGService svc = org.mockito.Mockito.mock(RAGService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
            Method m = RAGService.class.getDeclaredMethod("computeMotoCostPerKm", int.class, int.class);
            m.setAccessible(true);
            return (double) m.invoke(svc, fiscalPower, kmAnnual);
        }

        @Test @DisplayName("1-2 CV, 1000 km → 0.395")
        void unDeuxCv() throws Exception {
            assertThat(invokeBareme(2, 1000)).isEqualTo(0.395);
        }

        @Test @DisplayName("3-5 CV, 1000 km → 0.468")
        void troisCinqCv() throws Exception {
            assertThat(invokeBareme(4, 1000)).isEqualTo(0.468);
        }

        @Test @DisplayName("plus de 5 CV, 1000 km → 0.606 (non-régression de l'ancien comportement par défaut)")
        void plusDeCinqCv() throws Exception {
            assertThat(invokeBareme(6, 1000)).isEqualTo(0.606);
        }

        @Test @DisplayName("kmAnnual=0 → repli 0.412")
        void kmAnnualZero() throws Exception {
            assertThat(invokeBareme(6, 0)).isEqualTo(0.412);
        }
    }
}
