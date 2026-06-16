package io.multiagent.core.organization.service.keycloak;

import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Provisions Keycloak realms for new tenants.
 * Creates realm, public clients (frontend-admin, frontend-consultant),
 * bearer-only clients (ai-core-service, invoice-service),
 * roles (admin, manager, consultant) and an initial admin user.
 */
@Slf4j
@Service
public class KeycloakProvisioningService {

    @Value("${KEYCLOAK_ADMIN_URL:http://localhost:8090}")
    private String keycloakUrl;

    @Value("${KEYCLOAK_ADMIN_REALM:master}")
    private String adminRealm;

    @Value("${KEYCLOAK_ADMIN_CLIENT_ID:admin-cli}")
    private String adminClientId;

    @Value("${KEYCLOAK_ADMIN_USER:admin}")
    private String adminUser;

    @Value("${KEYCLOAK_ADMIN_PASSWORD:changeme}")
    private String adminPassword;

    /**
     * Provisions a complete Keycloak realm for a new tenant.
     *
     * @param realmName     the realm name (= organization slug)
     * @param adminEmail    email for the initial admin user
     * @param adminPwd      temporary password for the initial admin user
     */
    public void provisionRealm(String realmName, String adminEmail, String adminPwd) {
        try (Keycloak kc = buildAdminClient()) {
            createRealm(kc, realmName);
            createClients(kc, realmName);
            createRoles(kc, realmName);
            createAdminUser(kc, realmName, adminEmail, adminPwd);
            log.info("Keycloak realm '{}' provisioned successfully", realmName);
        }
    }

    /**
     * Deletes a Keycloak realm (used when deactivating a tenant).
     */
    public void deleteRealm(String realmName) {
        try (Keycloak kc = buildAdminClient()) {
            kc.realm(realmName).remove();
            log.info("Keycloak realm '{}' deleted", realmName);
        } catch (Exception e) {
            log.warn("Failed to delete Keycloak realm '{}': {}", realmName, e.getMessage());
        }
    }

    /**
     * Lists all users in the given Keycloak realm.
     */
    public List<UserRepresentation> listRealmUsers(String realmName) {
        try (Keycloak kc = buildAdminClient()) {
            return kc.realm(realmName).users().list(0, 500);
        }
    }

    /**
     * Creates a consultant user in Keycloak and assigns the 'consultant' role.
     *
     * @param realmName    the tenant realm
     * @param email        the consultant's email (must match external IdP email if applicable)
     * @param firstName    first name
     * @param lastName     last name
     * @param tempPassword temporary password for internal users, null for external IdP users
     * @return the Keycloak user ID
     */
    /**
     * Returns the lowercase emails of all users assigned to the given realm role.
     */
    public Set<String> getUserEmailsWithRole(String realmName, String roleName) {
        try (Keycloak kc = buildAdminClient()) {
            List<UserRepresentation> users = kc.realm(realmName).users().list(0, 500);
            log.info("[getUserEmailsWithRole] realm={} role={} totalUsers={}", realmName, roleName, users.size());
            Set<String> result = new HashSet<>();
            for (UserRepresentation u : users) {
                if (u.getEmail() == null || u.getId() == null) continue;
                try {
                    boolean hasRole = kc.realm(realmName).users().get(u.getId())
                            .roles().realmLevel().listEffective().stream()
                            .anyMatch(r -> roleName.equalsIgnoreCase(r.getName()));
                    if (hasRole) {
                        result.add(u.getEmail().toLowerCase());
                        log.info("[getUserEmailsWithRole] {} has role {}", u.getEmail(), roleName);
                    }
                } catch (Exception e) {
                    log.warn("[getUserEmailsWithRole] role check failed for user={} role={}: {}", u.getEmail(), roleName, e.getMessage());
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[getUserEmailsWithRole] failed role={} realm={}: {}", roleName, realmName, e.getMessage());
            return Set.of();
        }
    }

    public String createConsultantUser(String realmName, String email, String firstName,
                                       String lastName, String tempPassword) {
        try (Keycloak kc = buildAdminClient()) {
            UserRepresentation user = new UserRepresentation();
            user.setUsername(email);
            user.setEmail(email);
            user.setFirstName(firstName);
            user.setLastName(lastName);
            user.setEnabled(true);
            user.setEmailVerified(true);

            if (tempPassword != null && !tempPassword.isBlank()) {
                CredentialRepresentation cred = new CredentialRepresentation();
                cred.setType(CredentialRepresentation.PASSWORD);
                cred.setValue(tempPassword);
                cred.setTemporary(true);
                user.setCredentials(Collections.singletonList(cred));
            }

            try (Response res = kc.realm(realmName).users().create(user)) {
                if (res.getStatus() != 201) {
                    String body = res.readEntity(String.class);
                    throw new RuntimeException("Keycloak user creation failed (HTTP " + res.getStatus() + "): " + body);
                }
                String userId = res.getLocation().getPath().replaceAll(".*/", "");
                // Assign consultant role
                RoleRepresentation consultantRole = kc.realm(realmName).roles().get("consultant").toRepresentation();
                kc.realm(realmName).users().get(userId).roles().realmLevel()
                        .add(Collections.singletonList(consultantRole));
                log.info("Created consultant user '{}' with consultant role in realm '{}'", email, realmName);
                return userId;
            }
        }
    }

    private Keycloak buildAdminClient() {
        return KeycloakBuilder.builder()
                .serverUrl(keycloakUrl)
                .realm(adminRealm)
                .clientId(adminClientId)
                .username(adminUser)
                .password(adminPassword)
                .build();
    }

    private void createRealm(Keycloak kc, String realmName) {
        RealmRepresentation realm = new RealmRepresentation();
        realm.setRealm(realmName);
        realm.setEnabled(true);
        realm.setDisplayName(realmName);
        realm.setLoginTheme("ia-insight");
        realm.setRegistrationAllowed(false);
        realm.setResetPasswordAllowed(true);
        realm.setSslRequired("external");
        kc.realms().create(realm);
        log.info("Created realm '{}'", realmName);
    }

    private void createClients(Keycloak kc, String realmName) {
        // Public clients (PKCE S256) for frontends
        createPublicClient(kc, realmName, "frontend-admin");
        createPublicClient(kc, realmName, "frontend-consultant");

        // Bearer-only clients for backend services
        createBearerOnlyClient(kc, realmName, "ai-core-service");
        createBearerOnlyClient(kc, realmName, "invoice-service");
    }

    private void createPublicClient(Keycloak kc, String realmName, String clientId) {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(clientId);
        client.setPublicClient(true);
        client.setDirectAccessGrantsEnabled(true);
        client.setStandardFlowEnabled(true);
        client.setRedirectUris(List.of("*"));
        client.setWebOrigins(List.of("*"));
        client.setAttributes(java.util.Map.of(
                "pkce.code.challenge.method", "S256"
        ));
        try (Response res = kc.realm(realmName).clients().create(client)) {
            log.info("Created public client '{}' in realm '{}' (status: {})", clientId, realmName, res.getStatus());
        }
    }

    private void createBearerOnlyClient(Keycloak kc, String realmName, String clientId) {
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(clientId);
        client.setBearerOnly(true);
        try (Response res = kc.realm(realmName).clients().create(client)) {
            log.info("Created bearer-only client '{}' in realm '{}' (status: {})", clientId, realmName, res.getStatus());
        }
    }

    private void createRoles(Keycloak kc, String realmName) {
        for (String roleName : List.of("admin", "manager", "consultant")) {
            RoleRepresentation role = new RoleRepresentation();
            role.setName(roleName);
            role.setDescription("Role " + roleName + " for realm " + realmName);
            kc.realm(realmName).roles().create(role);
            log.info("Created role '{}' in realm '{}'", roleName, realmName);
        }
    }

    private void createAdminUser(Keycloak kc, String realmName, String email, String password) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(email);
        user.setEmail(email);
        user.setEnabled(true);
        user.setEmailVerified(true);

        CredentialRepresentation cred = new CredentialRepresentation();
        cred.setType(CredentialRepresentation.PASSWORD);
        cred.setValue(password);
        cred.setTemporary(true);
        user.setCredentials(Collections.singletonList(cred));

        try (Response res = kc.realm(realmName).users().create(user)) {
            if (res.getStatus() == 201) {
                String userId = res.getLocation().getPath().replaceAll(".*/", "");
                // Assign admin role
                RoleRepresentation adminRole = kc.realm(realmName).roles().get("admin").toRepresentation();
                kc.realm(realmName).users().get(userId).roles().realmLevel()
                        .add(Collections.singletonList(adminRole));
                log.info("Created admin user '{}' with admin role in realm '{}'", email, realmName);
            } else {
                log.warn("Failed to create admin user '{}' in realm '{}' (status: {})", email, realmName, res.getStatus());
            }
        }
    }
}
