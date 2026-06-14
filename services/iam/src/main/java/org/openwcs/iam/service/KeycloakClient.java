package org.openwcs.iam.service;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Thin Keycloak adapter for self-service password change. Two capabilities:
 *
 * <ol>
 *   <li><b>Verify</b> a user's CURRENT password with a direct grant against the public SPA client.
 *       Keycloak validates credentials before it checks pending required actions, so a correct
 *       password on an account that is "not fully set up" (a temporary password awaiting change)
 *       returns {@code invalid_grant} with the description "Account is not fully set up" rather than
 *       "Invalid user credentials" — we treat the former as a correct password. This lets a user
 *       whose account is currently un-loginable still prove their identity to change the password.</li>
 *   <li><b>Set</b> the new password with the confidential IAM service account
 *       (realm-management:manage-users): reset the password as permanent and clear pending required
 *       actions, so the account becomes loginable.</li>
 * </ol>
 *
 * Kept deliberately small and mockable so {@link ChangePasswordService} is unit-testable.
 */
@Component
public class KeycloakClient {

    private static final Logger log = LoggerFactory.getLogger(KeycloakClient.class);

    private final RestClient http;
    private final String realm;
    private final String webClientId;
    private final String iamClientId;
    private final String iamClientSecret;

    public KeycloakClient(
            RestClient.Builder builder,
            @Value("${openwcs.keycloak.base-url}") String baseUrl,
            @Value("${openwcs.keycloak.realm}") String realm,
            @Value("${openwcs.keycloak.web-client-id}") String webClientId,
            @Value("${openwcs.keycloak.iam-client-id}") String iamClientId,
            @Value("${openwcs.keycloak.iam-client-secret}") String iamClientSecret) {
        this.http = builder.baseUrl(baseUrl).build();
        this.realm = realm;
        this.webClientId = webClientId;
        this.iamClientId = iamClientId;
        this.iamClientSecret = iamClientSecret;
    }

    /** True when {@code password} is the user's current password (even if the account is awaiting a
     *  required action such as a forced password change). False on wrong credentials or unknown user. */
    public boolean verifyPassword(String username, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", webClientId);
        form.add("username", username);
        form.add("password", password);
        return http.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .exchange((request, response) -> {
                    if (response.getStatusCode().is2xxSuccessful()) {
                        return true; // credentials valid and account fully usable
                    }
                    String body = new String(response.getBody().readAllBytes());
                    // Correct password but a pending required action (e.g. a temporary password):
                    // Keycloak only reaches this state after the password itself validated.
                    return body.contains("not fully set up") || body.contains("not_fully_set_up");
                }, false);
    }

    /**
     * Set a permanent password for the user and clear pending required actions so the account is
     * immediately loginable. Throws {@link UserNotFoundException} when the username is unknown.
     */
    public void setPassword(String username, String newPassword) {
        String token = serviceAccountToken();
        String userId = findUserId(token, username);
        if (userId == null) {
            throw new UserNotFoundException(username);
        }
        // Reset as a permanent (non-temporary) credential.
        http.put()
                .uri("/admin/realms/{realm}/users/{id}/reset-password", realm, userId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("type", "password", "value", newPassword, "temporary", false))
                .retrieve()
                .toBodilessEntity();
        // Clear pending required actions (e.g. UPDATE_PASSWORD) so the account is no longer
        // "not fully set up" — identity was already proven by verifying the current password.
        http.put()
                .uri("/admin/realms/{realm}/users/{id}", realm, userId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("requiredActions", List.of()))
                .retrieve()
                .toBodilessEntity();
        log.info("password changed for user {} (permanent, required actions cleared)", username);
    }

    private String serviceAccountToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", iamClientId);
        form.add("client_secret", iamClientSecret);
        Map<?, ?> body = http.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        Object token = body == null ? null : body.get("access_token");
        if (token == null) {
            throw new IllegalStateException("Keycloak did not return a service-account token");
        }
        return token.toString();
    }

    @SuppressWarnings("unchecked")
    private String findUserId(String token, String username) {
        List<Map<String, Object>> users = http.get()
                .uri("/admin/realms/{realm}/users?username={u}&exact=true", realm, username)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(List.class);
        if (users == null || users.isEmpty()) {
            return null;
        }
        Object id = users.get(0).get("id");
        return id == null ? null : id.toString();
    }
}
