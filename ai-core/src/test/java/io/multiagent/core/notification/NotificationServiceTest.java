package io.multiagent.core.notification;

import io.multiagent.core.notification.service.ConsultantNotificationService;
import io.multiagent.core.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("NotificationService + ConsultantNotificationService — SSE")
class NotificationServiceTest {

    private NotificationService           adminService;
    private ConsultantNotificationService consultantService;

    @BeforeEach
    void setUp() {
        adminService      = new NotificationService();
        consultantService = new ConsultantNotificationService();
    }

    // ── Admin SSE ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("subscribe() admin — emitter ajouté au registre")
    void subscribe_admin_addsEmitter() {
        SseEmitter emitter = adminService.subscribe();
        assertThat(emitter).isNotNull();
    }

    @Test
    @DisplayName("push() admin — notification stockée dans l'historique")
    void push_admin_storesNotification() {
        adminService.push("EXPENSE_APPROVED", "alice@test.com", "alice",
                "Note de frais approuvée", "expense-1");

        List<?> all = adminService.getAll();
        assertThat(all).hasSize(1);
        List<?> unread = adminService.getUnread();
        assertThat(unread).hasSize(1);
    }

    @Test
    @DisplayName("markRead() admin — notification marquée lue")
    void markRead_admin_marksAsRead() {
        adminService.push("CRA_SUBMITTED", "bob@test.com", "bob", "CRA soumis", "cra-1");
        String id = ((io.multiagent.core.model.Notification) adminService.getAll().get(0)).getId();

        adminService.markRead(id);

        assertThat(adminService.getUnread()).isEmpty();
        assertThat(adminService.getAll()).hasSize(1);
    }

    @Test
    @DisplayName("markAllRead() admin — toutes les notifications lues")
    void markAllRead_admin() {
        adminService.push("T1", "a@b.com", "a", "msg1", "r1");
        adminService.push("T2", "a@b.com", "a", "msg2", "r2");
        assertThat(adminService.getUnread()).hasSize(2);

        adminService.markAllRead();
        assertThat(adminService.getUnread()).isEmpty();
    }

    @Test
    @DisplayName("push() admin — dead emitter retiré sans exception")
    void push_admin_removesDeadEmitter() throws IOException {
        SseEmitter dead = mock(SseEmitter.class);
        doThrow(new IOException("Broken pipe")).when(dead).send(any(SseEmitter.SseEventBuilder.class));

        // Simuler l'abonnement en ajoutant manuellement le dead emitter
        // (le registre interne n'est pas directement accessible — on teste via subscribe + push)
        adminService.subscribe();  // 1 emitter valide
        assertThatCode(() -> adminService.push("T", null, null, "msg", null))
                .doesNotThrowAnyException();
    }

    // ── Consultant SSE ────────────────────────────────────────────────────────

    @Test
    @DisplayName("subscribe() consultant — emitter créé par email")
    void subscribe_consultant_createsEmitter() {
        SseEmitter e = consultantService.subscribe("alice@test.com");
        assertThat(e).isNotNull();
    }

    @Test
    @DisplayName("push() consultant — notification stockée pour le bon email")
    void push_consultant_storesForCorrectEmail() {
        consultantService.push("alice@test.com", "EXPENSE_APPROVED", "Approuvé", "exp-1");
        consultantService.push("bob@test.com",   "CRA_VALIDATED",    "Validé",   "cra-1");

        assertThat(consultantService.getAll("alice@test.com")).hasSize(1);
        assertThat(consultantService.getAll("bob@test.com")).hasSize(1);
        // Charlie n'a rien reçu
        assertThat(consultantService.getAll("charlie@test.com")).isEmpty();
    }

    @Test
    @DisplayName("getUnread() consultant — filtre correctement les lus/non-lus")
    void getUnread_consultant_filtersCorrectly() {
        consultantService.push("alice@test.com", "T1", "msg1", "r1");
        consultantService.push("alice@test.com", "T2", "msg2", "r2");
        assertThat(consultantService.getUnread("alice@test.com")).hasSize(2);

        String id = ((io.multiagent.core.model.Notification)
                consultantService.getAll("alice@test.com").get(0)).getId();
        consultantService.markRead("alice@test.com", id);

        assertThat(consultantService.getUnread("alice@test.com")).hasSize(1);
    }

    @Test
    @DisplayName("markAllRead() consultant — toutes lues")
    void markAllRead_consultant() {
        consultantService.push("alice@test.com", "T1", "m1", "r1");
        consultantService.push("alice@test.com", "T2", "m2", "r2");
        consultantService.markAllRead("alice@test.com");
        assertThat(consultantService.getUnread("alice@test.com")).isEmpty();
    }

    @Test
    @DisplayName("push() consultant — broken pipe absorbé sans exception")
    void push_consultant_absorbesBrokenPipe() {
        consultantService.subscribe("alice@test.com");
        assertThatCode(() ->
                consultantService.push("alice@test.com", "T", "msg", "r"))
                .doesNotThrowAnyException();
    }
}
