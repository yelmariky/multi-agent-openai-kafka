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
    public ResponseEntity<Map<String, String>> deactivate(@PathVariable UUID id) {
        Organization org = organizationService.findById(id);
        if (org == null) return ResponseEntity.notFound().build();
        org.setActive(false);
        organizationService.update(id, org);
        return ResponseEntity.ok(Map.of("message", "Tenant desactivated", "slug", org.getSlug()));
    }
}
