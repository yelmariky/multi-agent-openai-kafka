package io.multiagent.core.organization.controller;

public record CreateTenantRequest(
        String name,
        String slug,
        String plan,
        String keycloakRealm,
        String adminEmail,
        String adminPassword
) {
    public String effectiveRealm() {
        return keycloakRealm != null ? keycloakRealm : slug;
    }

    public String effectivePlan() {
        return plan != null ? plan : "STARTER";
    }

    public String effectiveAdminEmail() {
        return adminEmail != null ? adminEmail : "admin@" + slug + ".local";
    }

    public String effectiveAdminPassword() {
        return adminPassword != null ? adminPassword : "changeme";
    }
}
