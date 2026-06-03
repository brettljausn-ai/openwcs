package org.openwcs.gtp.api;

import org.openwcs.gtp.domain.WorkplaceSession;

/**
 * Heartbeat / release result. {@code active} is true while the session still owns its workplace;
 * once it was taken over (or released) it is false and {@code reason} says why
 * ({@code superseded} | {@code released}) so the console can show a clear takeover message.
 */
public record SessionStatusView(boolean active, String reason) {

    public static SessionStatusView of(WorkplaceSession session) {
        return session.isActive()
                ? new SessionStatusView(true, null)
                : new SessionStatusView(false, session.getSupersededReason());
    }
}
