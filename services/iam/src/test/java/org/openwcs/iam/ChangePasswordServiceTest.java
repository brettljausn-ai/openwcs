package org.openwcs.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.openwcs.iam.api.InvalidCredentialsException;
import org.openwcs.iam.service.ChangePasswordService;
import org.openwcs.iam.service.KeycloakClient;

/** Self-service password change rules, with Keycloak mocked (no realm needed). */
class ChangePasswordServiceTest {

    private final KeycloakClient keycloak = Mockito.mock(KeycloakClient.class);
    private final ChangePasswordService service = new ChangePasswordService(keycloak);

    @Test
    void changesThePasswordWhenTheCurrentOneVerifies() {
        when(keycloak.verifyPassword("alice", "OldPass1!")).thenReturn(true);

        service.changePassword("alice", "OldPass1!", "NewPass1!");

        verify(keycloak).setPassword("alice", "NewPass1!");
    }

    @Test
    void rejectsAWrongCurrentPasswordAndNeverSets() {
        when(keycloak.verifyPassword("alice", "wrong")).thenReturn(false);

        assertThatThrownBy(() -> service.changePassword("alice", "wrong", "NewPass1!"))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(keycloak, never()).setPassword(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    void rejectsAShortNewPasswordBeforeTouchingKeycloak() {
        assertThatThrownBy(() -> service.changePassword("alice", "OldPass1!", "short"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(keycloak, never()).verifyPassword(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    void rejectsReusingTheCurrentPassword() {
        assertThatThrownBy(() -> service.changePassword("alice", "SamePass1!", "SamePass1!"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(keycloak, never()).verifyPassword(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    void rejectsBlankUsername() {
        assertThatThrownBy(() -> service.changePassword("  ", "OldPass1!", "NewPass1!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifyingTrueIsRequiredForTheSet() {
        when(keycloak.verifyPassword("bob", "OldPass1!")).thenReturn(true);
        service.changePassword("bob", "OldPass1!", "Different1!");
        assertThat(Mockito.mockingDetails(keycloak).getInvocations()).isNotEmpty();
        verify(keycloak).setPassword("bob", "Different1!");
    }
}
