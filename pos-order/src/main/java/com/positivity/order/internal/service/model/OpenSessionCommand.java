package com.positivity.order.internal.service.model;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Open a register session (parity G1; CAP:550 S16, #2512). The opening float is the register's
 * configured float from accounting (AW16) and the opener comes from the security context (ADR-0018),
 * so neither is part of the command.
 */
public record OpenSessionCommand(
        @NonNull String terminalId, @Nullable UUID locationId) {

    public OpenSessionCommand {
        Objects.requireNonNull(terminalId, "terminalId must not be null");
    }
}
