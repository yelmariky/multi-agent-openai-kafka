package io.multiagent.core.organization.controller;

import io.multiagent.core.organization.entity.Organization;
import io.multiagent.core.organization.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/organization")
public class TenantInfoController {

    private final OrganizationService organizationService;

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> currentTenant() {
        Organization org = organizationService.getCurrentTenant();
        if (org == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of(
                "id", org.getId(),
                "name", org.getName(),
                "slug", org.getSlug(),
                "plan", org.getPlan() != null ? org.getPlan() : ""
        ));
    }
}
