package io.multiagent.invoice.infrastructure.tenant;

import java.util.UUID;

public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_REALM = new ThreadLocal<>();

    private TenantContext() {}

    public static UUID getTenantId() {
        UUID tid = CURRENT_TENANT_ID.get();
        if (tid == null) {
            throw new IllegalStateException("TenantContext not set");
        }
        return tid;
    }

    public static UUID getTenantIdOrNull() {
        return CURRENT_TENANT_ID.get();
    }

    public static String getRealm() {
        return CURRENT_REALM.get();
    }

    public static void set(UUID tenantId, String realm) {
        CURRENT_TENANT_ID.set(tenantId);
        CURRENT_REALM.set(realm);
    }

    public static void clear() {
        CURRENT_TENANT_ID.remove();
        CURRENT_REALM.remove();
    }
}
