package com.positivity.order.internal.service.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Command to open a register session on a terminal (parity story G1, spec R6.1).
 *
 * @param terminalId terminal the drawer belongs to (one OPEN session per terminal)
 * @param locationId shop location; optional — defaults from the terminal's previous session
 * @param openingFloat starting drawer cash; optional — defaults to the previous counted close
 * @param openedByClerkId clerk opening the session
 */
public record OpenSessionCommand(
        @NonNull String terminalId,
        @Nullable UUID locationId,
        @Nullable BigDecimal openingFloat,
        @NonNull String openedByClerkId) {

    public OpenSessionCommand {
        Objects.requireNonNull(terminalId, "terminalId must not be null");
        Objects.requireNonNull(openedByClerkId, "openedByClerkId must not be null");
    }
}
