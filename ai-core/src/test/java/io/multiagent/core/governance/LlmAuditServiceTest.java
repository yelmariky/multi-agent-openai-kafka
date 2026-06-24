package io.multiagent.core.governance;

import io.multiagent.core.governance.entity.LlmAuditLog;
import io.multiagent.core.governance.repository.LlmAuditLogRepository;
import io.multiagent.core.infrastructure.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for LlmAuditService.
 * Verifies RGPD compliance (no raw input stored), tenant isolation, and resilience.
 */
@ExtendWith(MockitoExtension.class)
class LlmAuditServiceTest {

    @Mock
    private LlmAuditLogRepository repository;

    private LlmAuditService auditService;

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String SHA256_HELLO =
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"; // sha256("hello")

    @BeforeEach
    void setUp() {
        auditService = new LlmAuditService(repository);
        TenantContext.set(TENANT_ID, "test-realm");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // --- RGPD: raw input never stored ---

    @Test
    void record_shouldHashInputText_neverStoreRaw() {
        LlmCallContext ctx = LlmCallContext.builder()
                .feature("chat_json")
                .model("gpt-4.1-mini")
                .inputText("hello")
                .success(true)
                .durationMs(200)
                .build();

        auditService.record(ctx);

        ArgumentCaptor<LlmAuditLog> captor = ArgumentCaptor.forClass(LlmAuditLog.class);
        verify(repository).save(captor.capture());

        LlmAuditLog saved = captor.getValue();
        assertThat(saved.getInputHash()).isEqualTo(SHA256_HELLO);
        // Raw text must never appear in any field
        assertThat(saved.getResponseSummary()).isNull();
        assertThat(saved.getErrorMessage()).isNull();
    }

    @Test
    void record_nullInputText_shouldStoreNullHash() {
        LlmCallContext ctx = LlmCallContext.builder()
                .feature("embeddings_single")
                .model("text-embedding-3-large")
                .inputText(null)
                .success(true)
                .durationMs(50)
                .build();

        auditService.record(ctx);

        ArgumentCaptor<LlmAuditLog> captor = ArgumentCaptor.forClass(LlmAuditLog.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getInputHash()).isNull();
    }

    // --- Tenant isolation ---

    @Test
    void record_shouldCaptureCurrentTenant() {
        auditService.record(LlmCallContext.builder()
                .feature("chat_json")
                .model("gpt-4.1-mini")
                .success(true)
                .durationMs(100)
                .build());

        ArgumentCaptor<LlmAuditLog> captor = ArgumentCaptor.forClass(LlmAuditLog.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT_ID);
    }

    @Test
    void record_noTenantContext_shouldStoreNullTenantId() {
        TenantContext.clear();

        auditService.record(LlmCallContext.builder()
                .feature("chat_json")
                .model("gpt-4.1-mini")
                .success(true)
                .durationMs(100)
                .build());

        ArgumentCaptor<LlmAuditLog> captor = ArgumentCaptor.forClass(LlmAuditLog.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isNull();
    }

    // --- Success recording ---

    @Test
    void recordSuccess_shouldMarkSuccessTrue() {
        auditService.recordSuccess("chat_json", "gpt-4.1-mini", null, "ok", 150);

        ArgumentCaptor<LlmAuditLog> captor = ArgumentCaptor.forClass(LlmAuditLog.class);
        verify(repository).save(captor.capture());

        LlmAuditLog saved = captor.getValue();
        assertThat(saved.isSuccess()).isTrue();
        assertThat(saved.getErrorMessage()).isNull();
        assertThat(saved.getFeature()).isEqualTo("chat_json");
        assertThat(saved.getModel()).isEqualTo("gpt-4.1-mini");
        assertThat(saved.getDurationMs()).isEqualTo(150);
    }

    // --- Failure recording ---

    @Test
    void recordFailure_shouldMarkSuccessFalse_withErrorMessage() {
        auditService.recordFailure("embeddings_batch", "text-embedding-3-large", "Rate limit exceeded", 5000);

        ArgumentCaptor<LlmAuditLog> captor = ArgumentCaptor.forClass(LlmAuditLog.class);
        verify(repository).save(captor.capture());

        LlmAuditLog saved = captor.getValue();
        assertThat(saved.isSuccess()).isFalse();
        assertThat(saved.getErrorMessage()).contains("Rate limit exceeded");
    }

    // --- Resilience: repository failure must not propagate ---

    @Test
    void record_repositoryThrows_shouldNotPropagateException() {
        doThrow(new RuntimeException("DB down")).when(repository).save(any());

        // Must not throw — audit must never break business flow
        auditService.record(LlmCallContext.builder()
                .feature("chat_json")
                .model("gpt-4.1-mini")
                .success(true)
                .durationMs(100)
                .build());
    }

    // --- Response summary truncation (RGPD: limit stored data) ---

    @Test
    void record_longResponseSummary_shouldBeTruncatedAt500() {
        String longSummary = "x".repeat(600);

        auditService.record(LlmCallContext.builder()
                .feature("chat_json")
                .model("gpt-4.1-mini")
                .responseSummary(longSummary)
                .success(true)
                .durationMs(200)
                .build());

        ArgumentCaptor<LlmAuditLog> captor = ArgumentCaptor.forClass(LlmAuditLog.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getResponseSummary()).hasSizeLessThanOrEqualTo(501); // 500 + ellipsis char
    }

    // --- Token aggregation ---

    @Test
    void record_shouldSumPromptAndCompletionTokens() {
        auditService.record(LlmCallContext.builder()
                .feature("chat_json")
                .model("gpt-4.1-mini")
                .promptTokens(100)
                .completionTokens(50)
                .success(true)
                .durationMs(200)
                .build());

        ArgumentCaptor<LlmAuditLog> captor = ArgumentCaptor.forClass(LlmAuditLog.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTotalTokens()).isEqualTo(150);
    }
}
