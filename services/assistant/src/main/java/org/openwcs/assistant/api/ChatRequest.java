package org.openwcs.assistant.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/** POST /api/assistant/chat body. */
public record ChatRequest(
        @NotNull UUID warehouseId,
        @NotEmpty @Valid List<Turn> messages) {

    /** One conversation turn. {@code role} is "user" or "assistant". */
    public record Turn(@NotNull String role, @NotNull String content) {
    }
}
