package org.openwcs.iam.domain;

/**
 * The level of access an override grants a role or user on a screen (build.md §4.8). OFF is not a
 * stored value — it is the <em>absence</em> of a role/user row in {@link ScreenAccess}; only READ
 * (view-only) and WRITE (full) are persisted. The wire form is the lowercase name
 * ({@code "read"}/{@code "write"}) to match the UI's catalog (ui/src/auth/screens.ts).
 */
public enum AccessLevel {
    READ,
    WRITE;

    /** The lowercase wire token (e.g. {@code "write"}). */
    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Parse a wire token; {@code null}/blank/unknown (including "off"/"none") yields {@code null} (= OFF). */
    public static AccessLevel fromWire(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "write" -> WRITE;
            case "read" -> READ;
            default -> null;
        };
    }
}
