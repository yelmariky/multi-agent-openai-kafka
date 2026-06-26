package io.multiagent.core.settings.controller;

import io.multiagent.core.infrastructure.tenant.TenantContext;
import io.multiagent.core.model.ConsultantProfile;
import io.multiagent.core.organization.service.keycloak.KeycloakProvisioningService;
import io.multiagent.core.service.WeaviateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/consultants")
public class ConsultantController {

    private final WeaviateService weaviateService;
    private final KeycloakProvisioningService keycloakService;

    @GetMapping("/profiles")
    public ResponseEntity<List<ConsultantProfile>> getProfiles(
            @RequestParam(name = "company", required = false) String company) {
        return ResponseEntity.ok(weaviateService.findAllConsultantProfiles(company));
    }

    @PostMapping("/profiles")
    public ResponseEntity<Object> upsertProfile(@RequestBody ConsultantProfile profile) {
        try {
            java.util.UUID id = weaviateService.upsertConsultantProfile(profile);
            return ResponseEntity.ok(Map.of(
                    "id", id.toString(),
                    "email", profile.email() == null ? "" : profile.email()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Erreur : " + e.getMessage());
        }
    }

    @DeleteMapping("/profiles")
    public ResponseEntity<Object> deleteProfile(@RequestParam String email) {
        try {
            weaviateService.deleteConsultantProfile(email);
            return ResponseEntity.ok(Map.of("message", "Profil supprimé", "email", email));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Erreur : " + e.getMessage());
        }
    }

    /**
     * Invites a new consultant: creates Keycloak user (with consultant role) + consultant profile.
     * For internal users, a temporary password is set. For external IdP users, no password is set.
     */
    @PostMapping("/invite")
    public ResponseEntity<Object> inviteConsultant(@RequestBody Map<String, String> body) {
        String realm = TenantContext.getRealm();
        if (realm == null || realm.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Realm introuvable"));
        }

        String email     = body.getOrDefault("email", "").trim().toLowerCase();
        String firstName = body.getOrDefault("firstName", "").trim();
        String lastName  = body.getOrDefault("lastName", "").trim();
        String role      = body.getOrDefault("role", "Consultant").trim();
        String company   = body.getOrDefault("company", "").trim();
        String clientName = body.getOrDefault("clientName", "").trim();
        String tempPassword = body.get("tempPassword"); // null for external IdP users

        if (email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email obligatoire"));
        }

        try {
            // Dériver le rôle Keycloak depuis le rôle métier
            String keycloakRole = resolveKeycloakRole(role);

            // 1) Créer l'utilisateur Keycloak avec le bon rôle système
            String keycloakUserId = keycloakService.createUserWithRole(
                    realm, email, firstName, lastName, tempPassword, keycloakRole);

            // 2) Créer le profil en DB — isConsultant dérivé automatiquement via billable()
            String fullName = (firstName + " " + lastName).trim();
            if (fullName.isBlank()) fullName = email;
            ConsultantProfile profile = new ConsultantProfile(
                    null, email, fullName, role,
                    company.isBlank() ? realm.toUpperCase() : company,
                    clientName, "", "", null, 0.0, true,
                    io.multiagent.core.model.VehicleType.CAR, 7, 4999, null, null);
            weaviateService.upsertConsultantProfile(profile);

            log.info("Invited consultant '{}' in realm '{}' (keycloakId={})", email, realm, keycloakUserId);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "message", "Consultant invite",
                    "email", email,
                    "keycloakUserId", keycloakUserId));
        } catch (Exception e) {
            log.error("Failed to invite consultant '{}' in realm '{}': {}", email, realm, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Mappe le rôle métier vers le rôle Keycloak système. */
    private String resolveKeycloakRole(String role) {
        if (role == null) return "consultant";
        return switch (role.toLowerCase()) {
            case "admin"        -> "admin";
            case "manager", "gestionnaire" -> "manager";
            default             -> "consultant";
        };
    }

    @GetMapping("/keycloak-users")
    public ResponseEntity<List<Map<String, String>>> listKeycloakUsers() {
        String realm = TenantContext.getRealm();
        if (realm == null || realm.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            Set<String> staffEmails = new HashSet<>(keycloakService.getUserEmailsWithRole(realm, "admin"));
            staffEmails.addAll(keycloakService.getUserEmailsWithRole(realm, "manager"));
            List<UserRepresentation> users = keycloakService.listRealmUsers(realm);
            List<Map<String, String>> result = users.stream()
                    .filter(u -> u.getEmail() != null && !u.getEmail().isBlank())
                    .filter(u -> !staffEmails.contains(u.getEmail().toLowerCase()))
                    .map(u -> Map.of(
                            "email", u.getEmail(),
                            "name", u.getFirstName() != null && u.getLastName() != null
                                    ? u.getFirstName() + " " + u.getLastName()
                                    : u.getUsername(),
                            "username", u.getUsername() != null ? u.getUsername() : ""
                    ))
                    .toList();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to list Keycloak users for realm '{}': {}", realm, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

}
