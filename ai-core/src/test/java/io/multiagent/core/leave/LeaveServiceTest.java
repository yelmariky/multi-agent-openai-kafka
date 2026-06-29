package io.multiagent.core.leave;

import io.multiagent.core.infrastructure.kafka.EventPublisher;
import io.multiagent.core.infrastructure.kafka.NotificationPayload;
import io.multiagent.core.infrastructure.tenant.TenantContext;
import io.multiagent.core.leave.entity.LeaveBalanceEntity;
import io.multiagent.core.leave.entity.LeaveRequestEntity;
import io.multiagent.core.leave.repository.LeaveBalanceRepository;
import io.multiagent.core.leave.repository.LeaveRequestRepository;
import io.multiagent.core.leave.service.LeaveService;
import io.multiagent.core.service.WeaviateService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LeaveService — workflow congés")
class LeaveServiceTest {

    @Mock LeaveRequestRepository leaveRepo;
    @Mock LeaveBalanceRepository balanceRepo;
    @Mock WeaviateService        weaviateService;
    @Mock EventPublisher         eventPublisher;

    @InjectMocks LeaveService leaveService;

    private static final UUID   TENANT  = UUID.randomUUID();
    private static final String EMAIL   = "alice@test.com";

    @BeforeEach
    void mockTenant() {
        // TenantContext est un ThreadLocal — on le mocke via MockedStatic dans chaque test
    }

    @Test
    @DisplayName("request() — crée la demande DEMANDEE et notifie l'admin")
    void request_createsLeaveAndNotifiesAdmin() {
        try (MockedStatic<TenantContext> tc = mockStatic(TenantContext.class)) {
            tc.when(TenantContext::getTenantId).thenReturn(TENANT);
            when(leaveRepo.save(any())).thenAnswer(inv -> {
                LeaveRequestEntity e = inv.getArgument(0);
                e.setId(UUID.randomUUID());
                return e;
            });

            var result = leaveService.request(EMAIL, "CP", "2026-07-14", "2026-07-18", "Congés été");

            assertThat(result.get("status")).isEqualTo("DEMANDEE");
            assertThat(result.get("consultantEmail")).isEqualTo(EMAIL);
            verify(weaviateService).applyPendingLeaveAbsences(eq(EMAIL),
                    eq(LocalDate.of(2026, 7, 14)), eq(LocalDate.of(2026, 7, 18)), eq(TENANT));
            verify(eventPublisher).notify(eq(NotificationPayload.TARGET_ADMIN),
                    eq(EMAIL), eq("LEAVE_REQUESTED"), anyString(), anyString());
        }
    }

    @Test
    @DisplayName("request() — dates invalides (fin avant début) lève IllegalArgumentException")
    void request_invalidDateRange_throwsException() {
        try (MockedStatic<TenantContext> tc = mockStatic(TenantContext.class)) {
            tc.when(TenantContext::getTenantId).thenReturn(TENANT);
            assertThatThrownBy(() ->
                leaveService.request(EMAIL, "CP", "2026-07-18", "2026-07-14", null)
            ).isInstanceOf(IllegalArgumentException.class)
             .hasMessageContaining("après la date de début");
        }
    }

    @Test
    @DisplayName("approve() — status APPROUVEE + CRA mis à jour + notification consultant")
    void approve_approvesLeaveAndUpdatesCra() {
        UUID id = UUID.randomUUID();
        LeaveRequestEntity entity = buildEntity(id, "DEMANDEE");

        try (MockedStatic<TenantContext> tc = mockStatic(TenantContext.class)) {
            tc.when(TenantContext::getTenantId).thenReturn(TENANT);
            when(leaveRepo.findById(id)).thenReturn(Optional.of(entity));
            when(leaveRepo.save(any())).thenReturn(entity);
            when(balanceRepo.findByTenantIdAndConsultantEmailIgnoreCaseAndYear(any(), any(), anyInt()))
                .thenReturn(Optional.of(buildBalance()));

            var result = leaveService.approve(id, "admin@test.com");

            assertThat(result.get("status")).isEqualTo("APPROUVEE");
            verify(weaviateService).applyLeaveAbsences(eq(EMAIL), eq(id),
                    any(LocalDate.class), any(LocalDate.class), eq(TENANT));
            verify(eventPublisher).notify(eq(NotificationPayload.TARGET_CONSULTANT),
                    eq(EMAIL), eq("LEAVE_APPROVED"), anyString(), anyString());
        }
    }

    @Test
    @DisplayName("refuse() — status REFUSEE + absences retirées du CRA + notification consultant")
    void refuse_removesAbsencesAndNotifiesConsultant() {
        UUID id = UUID.randomUUID();
        LeaveRequestEntity entity = buildEntity(id, "DEMANDEE");

        try (MockedStatic<TenantContext> tc = mockStatic(TenantContext.class)) {
            tc.when(TenantContext::getTenantId).thenReturn(TENANT);
            when(leaveRepo.findById(id)).thenReturn(Optional.of(entity));
            when(leaveRepo.save(any())).thenReturn(entity);

            var result = leaveService.refuse(id, "Formation planifiée");

            assertThat(result.get("status")).isEqualTo("REFUSEE");
            assertThat(result.get("refusedReason")).isEqualTo("Formation planifiée");
            verify(weaviateService).removeLeaveAbsences(eq(EMAIL),
                    any(LocalDate.class), any(LocalDate.class), eq(TENANT));
            verify(eventPublisher).notify(eq(NotificationPayload.TARGET_CONSULTANT),
                    eq(EMAIL), eq("LEAVE_REFUSED"), contains("Formation planifiée"), anyString());
        }
    }

    @Test
    @DisplayName("approve() — entité appartenant à un autre tenant lève IllegalArgumentException")
    void approve_wrongTenant_throwsSecurityException() {
        UUID id = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        LeaveRequestEntity entity = buildEntity(id, "DEMANDEE");
        entity.setTenantId(otherTenant);   // mauvais tenant

        try (MockedStatic<TenantContext> tc = mockStatic(TenantContext.class)) {
            tc.when(TenantContext::getTenantId).thenReturn(TENANT);
            when(leaveRepo.findById(id)).thenReturn(Optional.of(entity));
            // Le filtre tenantId.equals(e.getTenantId()) échoue → orElseThrow
            assertThatThrownBy(() -> leaveService.approve(id, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("introuvable");
        }
    }

    // --- helpers ---

    private LeaveRequestEntity buildEntity(UUID id, String status) {
        LeaveRequestEntity e = new LeaveRequestEntity();
        e.setId(id);
        e.setTenantId(TENANT);
        e.setConsultantEmail(EMAIL);
        e.setType("CP");
        e.setStartDate(LocalDate.of(2026, 7, 14));
        e.setEndDate(LocalDate.of(2026,   7, 18));
        e.setDaysCount(BigDecimal.valueOf(5));
        e.setStatus(status);
        return e;
    }

    private LeaveBalanceEntity buildBalance() {
        LeaveBalanceEntity b = new LeaveBalanceEntity();
        b.setCpInitial(BigDecimal.valueOf(25));
        b.setCpTaken(BigDecimal.ZERO);
        b.setRttInitial(BigDecimal.valueOf(12));
        b.setRttTaken(BigDecimal.ZERO);
        return b;
    }
}
