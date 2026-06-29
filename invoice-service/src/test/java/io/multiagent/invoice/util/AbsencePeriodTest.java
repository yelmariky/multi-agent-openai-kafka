package io.multiagent.invoice.util;

import io.multiagent.invoice.model.AbsencePeriod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AbsencePeriod — modèle période d'absence")
class AbsencePeriodTest {

    @Test
    @DisplayName("getFrom / getTo retournent les valeurs définies")
    void gettersReturnSetValues() {
        AbsencePeriod ap = new AbsencePeriod();
        ap.setFrom("2026-07-14");
        ap.setTo("2026-07-18");
        assertThat(ap.getFrom()).isEqualTo("2026-07-14");
        assertThat(ap.getTo()).isEqualTo("2026-07-18");
    }

    @Test
    @DisplayName("Valeurs null acceptées sans exception")
    void nullValuesAccepted() {
        AbsencePeriod ap = new AbsencePeriod();
        ap.setFrom(null);
        ap.setTo(null);
        assertThat(ap.getFrom()).isNull();
        assertThat(ap.getTo()).isNull();
    }

    @Test
    @DisplayName("equals() et hashCode() cohérents")
    void equalsAndHashCode() {
        AbsencePeriod a1 = new AbsencePeriod();
        a1.setFrom("2026-07-14");
        a1.setTo("2026-07-18");
        AbsencePeriod a2 = new AbsencePeriod();
        a2.setFrom("2026-07-14");
        a2.setTo("2026-07-18");
        assertThat(a1).isEqualTo(a2);
        assertThat(a1.hashCode()).isEqualTo(a2.hashCode());
    }

    @Test
    @DisplayName("toString() non null")
    void toStringNotNull() {
        AbsencePeriod ap = new AbsencePeriod();
        ap.setFrom("2026-06-01");
        ap.setTo("2026-06-05");
        assertThat(ap.toString()).isNotNull();
    }
}
