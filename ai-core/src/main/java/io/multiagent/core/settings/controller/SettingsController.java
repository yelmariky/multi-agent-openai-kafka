package io.multiagent.core.settings.controller;

import io.multiagent.core.model.SellerProfile;
import io.multiagent.core.organization.service.OrganizationService;
import io.multiagent.core.service.WeaviateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/settings")
public class SettingsController {

    private final WeaviateService weaviateService;
    private final OrganizationService organizationService;

    public SettingsController(WeaviateService weaviateService, OrganizationService organizationService) {
        this.weaviateService = weaviateService;
        this.organizationService = organizationService;
    }

    /**
     * Returns the SellerProfile for the current tenant.
     * When none exists yet, returns an empty profile pre-filled with the tenant's
     * organization name — never "IA-INSIGHT" which is the platform vendor name.
     */
    @GetMapping("/seller")
    public ResponseEntity<SellerProfile> getSeller() {
        String tenantCompanyName = organizationService.getCurrentTenantName();
        SellerProfile profile = weaviateService.findSellerProfile(tenantCompanyName);
        if (profile == null) {
            profile = new SellerProfile(tenantCompanyName, "", "", "", "", "", "", "");
        }
        return ResponseEntity.ok(profile);
    }

    /**
     * Creates or replaces the SellerProfile for the current tenant.
     */
    @PostMapping("/seller")
    public ResponseEntity<Object> saveSeller(@RequestBody SellerProfile profile) {
        try {
            weaviateService.upsertSellerProfile(profile);
            return ResponseEntity.ok(Map.of(
                    "message", "Profil vendeur sauvegardé",
                    "company", profile.companyName() == null ? "" : profile.companyName()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Erreur lors de la sauvegarde : " + e.getMessage());
        }
    }
}
