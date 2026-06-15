package org.openwcs.slotting.api;

import java.util.List;
import org.openwcs.common.security.AccessControl;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Role gate for execution-layer dispatch writes (re-slot / replenishment / decant). Dispatching a
 * plan as a physical move is an operator action: it requires the OPERATOR or ADMIN role. When the
 * gateway forwards no roles (security disabled / internal call) the gate is open, mirroring the
 * other slotting writes that rely on the edge for enforcement.
 */
final class DispatchAccess {

    private DispatchAccess() {
    }

    static void requireOperator(String rolesHeader) {
        if (rolesHeader == null) {
            return; // security disabled / unscoped internal call — edge already enforced.
        }
        List<String> roles = AccessControl.parseRoles(rolesHeader);
        if (!roles.contains("OPERATOR") && !roles.contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Dispatching moves requires the OPERATOR or ADMIN role.");
        }
    }
}
