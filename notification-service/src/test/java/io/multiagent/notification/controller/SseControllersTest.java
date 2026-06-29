package io.multiagent.notification.controller;

import io.multiagent.notification.service.AdminSseRegistry;
import io.multiagent.notification.service.ConsultantSseRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SSE Controllers — endpoints stream")
class SseControllersTest {

    @Mock AdminSseRegistry      adminRegistry;
    @Mock ConsultantSseRegistry consultantRegistry;

    @InjectMocks AdminSseController      adminController;
    @InjectMocks ConsultantSseController consultantController;

    @Test
    @DisplayName("AdminSseController.stream() délègue au registre")
    void adminStreamDelegatesToRegistry() {
        SseEmitter emitter = new SseEmitter(0L);
        when(adminRegistry.subscribe(0)).thenReturn(emitter);

        SseEmitter result = adminController.stream();

        assertThat(result).isSameAs(emitter);
        verify(adminRegistry).subscribe(0);
    }

    @Test
    @DisplayName("AdminSseController.health() retourne un statut OK")
    void adminHealthReturnsOk() {
        when(adminRegistry.connectedCount()).thenReturn(2);

        String health = adminController.health();

        assertThat(health).contains("notification-service OK").contains("2");
    }

    @Test
    @DisplayName("ConsultantSseController.stream() délègue au registre avec l'email")
    void consultantStreamDelegatesToRegistry() {
        SseEmitter emitter = new SseEmitter(0L);
        when(consultantRegistry.subscribe("alice@test.com")).thenReturn(emitter);

        SseEmitter result = consultantController.stream("alice@test.com");

        assertThat(result).isSameAs(emitter);
        verify(consultantRegistry).subscribe("alice@test.com");
    }
}
