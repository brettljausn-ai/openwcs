package org.openwcs.iam.service;

/** Internal signal: no Keycloak user with that username. Callers map it to a 401 (do not reveal
 *  whether an account exists). */
public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(String username) {
        super("No user: " + username);
    }
}
