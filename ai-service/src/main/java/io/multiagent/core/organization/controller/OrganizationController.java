package io.multiagent.core.organization.controller;

import io.multiagent.core.organization.entity.Organization;
import io.multiagent.core.organization.service.OrganizationService;
import io.multiagent.core.organization.service.keycloak.KeycloakProvisioningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/platform/tenants")
public class OrganizationController {

    private final OrganizationService organizationService;
    private final KeycloakProvisioningService keycloakProvisioning;

    @GetMapping
    public List<Organization> list() {
        return organizationService.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Organization> get(@PathVariable UUID id) {
        Organization org = organizationService.findById(id);
        return org != null ? ResponseEntity.ok(org) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public ResponseEntity<Object> create(@RequestBody CreateTenantRequest request) {
        // 1. Create organization in database
        Organization org = new Organization();
        org.setName(request.name());
        org.setSlug(request.slug());
        org.setPlan(request.effectivePlan());
        org.setKeycloakRealm(request.effectiveRealm());

        Organization created = organizationService.create(org);

        // 2. Provision Keycloak realm (best-effort: log error but don't rollback DB)
        try {
            keycloakProvisioning.provisionRealm(
                    request.effectiveRealm(),
                    request.effectiveAdminEmail(),
                    request.effectiveAdminPassword()
            );
        } catch (Exception e) {
            log.error("Keycloak provisioning failed for realm '{}': {}", request.effectiveRealm(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.MULTI_STATUS).body(Map.of(
                    "organization", created,
                    "keycloakProvisioned", false,
                    "keycloakError", e.getMessage()
            ));
        }

        return ResponseEntity.ok(Map.of(
                "organization", created,
                "keycloakProvisioned", true
        ));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Organization> update(@PathVariable UUID id, @RequestBody Organization updates) {
        return ResponseEntity.ok(organizationService.update(id, updates));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deactivate(@PathVariable UUID id) {
        Organization org = organizationService.findById(id);
        if (org == null) return ResponseEntity.notFound().build();

        // 1. Désactiver le realm Keycloak — bloque les nouveaux logins et révoque les sessions
        boolean keycloakDisabled = true;
        try {
            keycloakProvisioning.disableRealm(org.getKeycloakRealm());
        } catch (Exception e) {
            log.error("Keycloak disableRealm failed for '{}': {}", org.getKeycloakRealm(), e.getMessage());
            keycloakDisabled = false;
        }

        // 2. Marquer l'organisation inactive en base
        org.setActive(false);
        organizationService.update(id, org);

        log.info("Tenant désactivé — slug={} keycloakDisabled={}", org.getSlug(), keycloakDisabled);
        return ResponseEntity.ok(Map.of(
                "message", "Tenant désactivé",
                "slug", org.getSlug(),
                "keycloakDisabled", keycloakDisabled
        ));
    }

    @PutMapping("/{id}/reactivate")
    public ResponseEntity<Map<String, Object>> reactivate(@PathVariable UUID id) {
        Organization org = organizationService.findById(id);
        if (org == null) return ResponseEntity.notFound().build();

        boolean keycloakEnabled = true;
        try {
            keycloakProvisioning.enableRealm(org.getKeycloakRealm());
        } catch (Exception e) {
            log.error("Keycloak enableRealm failed for '{}': {}", org.getKeycloakRealm(), e.getMessage());
            keycloakEnabled = false;
        }

        org.setActive(true);
        organizationService.update(id, org);

        log.info("Tenant réactivé — slug={} keycloakEnabled={}", org.getSlug(), keycloakEnabled);
        return ResponseEntity.ok(Map.of(
                "message", "Tenant réactivé",
                "slug", org.getSlug(),
                "keycloakEnabled", keycloakEnabled
        ));
    }
}
