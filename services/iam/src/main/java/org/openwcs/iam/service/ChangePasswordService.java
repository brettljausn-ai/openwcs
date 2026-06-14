package org.openwcs.iam.service;

import org.openwcs.iam.api.InvalidCredentialsException;
import org.springframework.stereotype.Service;

/**
 * Self-service password change (used by the login screen, so it works even for an account that is
 * currently "not fully set up" because of a forced/temporary password). The user proves their
 * identity with their CURRENT password; on success the new password is set as permanent and any
 * pending required actions are cleared, so the account becomes loginable.
 */
@Service
public class ChangePasswordService {

    /** Minimum new-password length enforced here (the realm has no password policy configured). */
    private static final int MIN_LENGTH = 8;

    private final KeycloakClient keycloak;

    public ChangePasswordService(KeycloakClient keycloak) {
        this.keycloak = keycloak;
    }

    public void changePassword(String username, String currentPassword, String newPassword) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username is required.");
        }
        if (currentPassword == null || currentPassword.isEmpty()) {
            throw new IllegalArgumentException("Current password is required.");
        }
        if (newPassword == null || newPassword.length() < MIN_LENGTH) {
            throw new IllegalArgumentException("New password must be at least " + MIN_LENGTH + " characters.");
        }
        if (newPassword.equals(currentPassword)) {
            throw new IllegalArgumentException("New password must be different from the current one.");
        }
        if (!keycloak.verifyPassword(username, currentPassword)) {
            throw new InvalidCredentialsException();
        }
        try {
            keycloak.setPassword(username, newPassword);
        } catch (UserNotFoundException e) {
            // Verification passed, so this should not happen; treat as auth failure rather than
            // revealing account existence.
            throw new InvalidCredentialsException();
        }
    }
}
