package org.openwcs.iam.api;

/** The supplied current password did not match (or the account could not be verified). Mapped to 401. */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Current password is incorrect.");
    }
}
