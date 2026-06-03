package org.openwcs.masterdata.api;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Distinguishes interactive (UI / human) callers from the internal host-sync ingestion path so
 * the WCS can keep SKU / UoM / barcode read-only for users while still letting the host upsert them.
 *
 * <p>Interactive traffic always reaches master-data through the API gateway, which validates the
 * Keycloak JWT and injects the trusted {@code X-Auth-User} (and {@code X-Auth-Roles}) identity
 * headers — and strips any client-supplied copies, so they cannot be spoofed (gateway
 * {@code IdentityPropagationFilter}, build.md §12). The host integration service, by contrast,
 * calls master-data directly service-to-service ({@code http://master-data:8081}, bypassing the
 * gateway) and therefore never carries those identity headers. The presence of {@code X-Auth-User}
 * is thus a reliable "this is an end-user request" signal: such requests are blocked from writing
 * host-owned master data; header-less internal calls (host sync) are allowed.
 */
public final class HostManagedGuard {

    /** Header injected by the gateway for every authenticated, gateway-routed (interactive) request. */
    static final String INTERACTIVE_IDENTITY_HEADER = "X-Auth-User";

    private HostManagedGuard() {
    }

    /**
     * Rejects the call with HTTP 403 if it originates from an interactive (gateway-routed) caller.
     * The host-sync path (no gateway identity header) passes through and may upsert.
     *
     * @param request the current request
     * @param entity  human-readable name of the host-owned entity, used in the error detail
     */
    public static void rejectInteractiveWrite(HttpServletRequest request, String entity) {
        if (isInteractive(request)) {
            throw new HostManagedException(entity);
        }
    }

    private static boolean isInteractive(HttpServletRequest request) {
        String user = request.getHeader(INTERACTIVE_IDENTITY_HEADER);
        return user != null && !user.isBlank();
    }
}
