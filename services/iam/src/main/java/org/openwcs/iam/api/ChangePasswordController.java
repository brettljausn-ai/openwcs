package org.openwcs.iam.api;

import jakarta.validation.constraints.NotBlank;
import org.openwcs.iam.service.ChangePasswordService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service password change for the login screen. This endpoint is PUBLIC (reachable without a
 * token): a user whose account is "not fully set up" (forced/temporary password) cannot obtain a
 * token, so the change must be done pre-login. Identity is proven by the current password inside the
 * service; the gateway permits this exact path unauthenticated.
 */
@RestController
@RequestMapping("/api/iam/change-password")
public class ChangePasswordController {

    private final ChangePasswordService service;

    public ChangePasswordController(ChangePasswordService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Void> changePassword(@RequestBody ChangePasswordRequest request) {
        service.changePassword(request.username(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    public record ChangePasswordRequest(
            @NotBlank String username,
            @NotBlank String currentPassword,
            @NotBlank String newPassword) {
    }
}
