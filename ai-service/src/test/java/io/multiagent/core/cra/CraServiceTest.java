package io.multiagent.core.cra;

import io.multiagent.core.cra.service.CraService;
import io.multiagent.core.infrastructure.kafka.EventPublisher;
import io.multiagent.core.infrastructure.kafka.KafkaTopics;
import io.multiagent.core.leave.repository.LeaveRequestRepository;
import io.multiagent.core.model.CraDayEntry;
import io.multiagent.core.model.CraRequest;
import io.multiagent.core.service.WeaviateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CraService — workflow CRA")
class CraServiceTest {

    @Mock WeaviateService       weaviateService;
    @Mock LeaveRequestRepository leaveRepo;
    @Mock EventPublisher         eventPublisher;

    @InjectMocks CraService craService;

    private CraRequest brouillon;

    @BeforeEach
    void setUp() {
        brouillon = new CraRequest(
                "cra-1", "alice@test.com", "IA-INSIGHT", "CLIENT SA",
                "client@sa.com", "2026-06",
                List.of(new CraDayEntry("2026-06-01", 1.0, "TRAVAIL", "proj-1")),
                1.0, "BROUILLON", null, null, null, null, null, "proj-1", null, null
        );
        when(weaviateService.indexCra(any())).thenAnswer(inv -> {
            CraRequest r = inv.getArgument(0);
            return r.id() != null ? r.id() : "new-id";
        });
    }

    @Test
    @DisplayName("save() — totalDays calculé depuis les entrées TRAVAIL")
    void save_computesTotalDays() {
        CraRequest input = new CraRequest(
                null, "alice@test.com", "IA-INSIGHT", "CLIENT", "c@c.com",
                "2026-06",
                List.of(
                    new CraDayEntry("2026-06-01", 1.0,  "TRAVAIL",  "p1"),
                    new CraDayEntry("2026-06-02", 0.5,  "TRAVAIL",  "p1"),
                    new CraDayEntry("2026-06-03", 1.0,  "ABSENT",   "__LEAVE__")
                ),
                0.0, null, null, null, null, null, null, "p1", null, null
        );

        craService.save(input);

        ArgumentCaptor<CraRequest> cap = ArgumentCaptor.forClass(CraRequest.class);
        verify(weaviateService).indexCra(cap.capture());
        assertThat(cap.getValue().totalDays()).isEqualTo(1.5);   // ABSENT ne compte pas
        assertThat(cap.getValue().status()).isEqualTo("BROUILLON");
    }

    @Test
    @DisplayName("submit() — status SOUMIS + event Kafka CRA_SUBMITTED + notification admin")
    void submit_setsStatusAndPublishesKafkaEvent() {
        CraRequest submitted = craService.submit(brouillon);

        assertThat(submitted.status()).isEqualTo("SOUMIS");
        assertThat(submitted.submittedAt()).isNotBlank();

        verify(eventPublisher).notify(eq("ADMIN"), eq("alice@test.com"),
                eq("CRA_SUBMITTED"), anyString(), anyString());
        verify(eventPublisher).publish(eq(KafkaTopics.CRA_SUBMITTED), any());
    }

    @Test
    @DisplayName("validate() — status VALIDE + event Kafka + notification consultant")
    void validate_setsStatusAndNotifiesConsultant() {
        CraRequest soumis = craService.submit(brouillon);
        CraRequest validated = craService.validate(soumis, "admin@test.com");

        assertThat(validated.status()).isEqualTo("VALIDE");
        assertThat(validated.validatedBy()).isEqualTo("admin@test.com");
        assertThat(validated.validatedAt()).isNotBlank();

        verify(eventPublisher).notify(eq("CONSULTANT"), eq("alice@test.com"),
                eq("CRA_VALIDATED"), anyString(), anyString());
        verify(eventPublisher).publish(eq(KafkaTopics.CRA_VALIDATED), any());
    }

    @Test
    @DisplayName("refuse() — status REFUSE + motif conservé + notification consultant")
    void refuse_setsStatusAndReason() {
        CraRequest soumis = craService.submit(brouillon);
        CraRequest refused = craService.refuse(soumis, "Jours incohérents");

        assertThat(refused.status()).isEqualTo("REFUSE");
        assertThat(refused.refusedReason()).isEqualTo("Jours incohérents");
        assertThat(refused.submittedAt()).isNull();

        verify(eventPublisher).notify(eq("CONSULTANT"), eq("alice@test.com"),
                eq("CRA_REFUSED"), contains("Jours incohérents"), anyString());
        verify(eventPublisher).publish(eq(KafkaTopics.CRA_REFUSED), any());
    }

    @Test
    @DisplayName("recall() — SOUMIS → BROUILLON, submittedAt effacé")
    void recall_resetsToBrouillon() {
        CraRequest soumis = craService.submit(brouillon);
        CraRequest recalled = craService.recall(soumis);

        assertThat(recalled.status()).isEqualTo("BROUILLON");
        assertThat(recalled.submittedAt()).isNull();
        // recall ne notifie pas
        verify(eventPublisher, never()).notify(any(), any(), eq("CRA_RECALLED"), any(), any());
    }

    @Test
    @DisplayName("save() — status null devient BROUILLON par défaut")
    void save_defaultsNullStatusToBrouillon() {
        CraRequest withNullStatus = new CraRequest(
                "x", "a@b.com", "C", "CL", null, "2026-06",
                List.of(), 0.0, null, null, null, null, null, null, null, null, null
        );
        craService.save(withNullStatus);

        ArgumentCaptor<CraRequest> cap = ArgumentCaptor.forClass(CraRequest.class);
        verify(weaviateService).indexCra(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo("BROUILLON");
    }
}
