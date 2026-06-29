package io.multiagent.activity.infrastructure.tenant;

import org.springframework.core.task.TaskDecorator;

import java.util.UUID;

/**
 * Propagates TenantContext from the calling thread to async task threads.
 * Register this in the async executor configuration.
 */
public class TenantAwareTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        UUID tenantId = TenantContext.getTenantIdOrNull();
        String realm = TenantContext.getRealm();
        return () -> {
            try {
                if (tenantId != null) {
                    TenantContext.set(tenantId, realm);
                }
                runnable.run();
            } finally {
                TenantContext.clear();
            }
        };
    }
}
