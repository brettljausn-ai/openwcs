package org.openwcs.processdesigner.api;

import jakarta.validation.constraints.NotBlank;

/** Reassign a running instance to another operator (supervisor-gated). {@code started_by} is kept. */
public record ReassignRequest(
        @NotBlank String toUser) {
}
