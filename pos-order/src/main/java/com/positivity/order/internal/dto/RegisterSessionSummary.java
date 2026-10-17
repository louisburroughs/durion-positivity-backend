package com.positivity.order.internal.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Read model of a register session (parity stories G1/G2).
 *
 * @param sessionId session identifier
 * @param terminalId terminal the drawer belongs to
 * @param locationId shop location
 * @param openedByClerkId clerk who opened the session
 * @param status OPEN / CLOSING / CLOSED
 * @param openingFloat starting drawer cash
 * @param countedCash physical cash counted at begin-close (null until then)
 * @param theoreticalCash expected cash snapshotted at close (null until then)
 * @param overShort countedCash − theoreticalCash (null until closed)
 * @param varianceApproved whether an over/short beyond the limit was approved at close
 * @param currencyCode ISO 4217 code of every amount, stamped when the drawer opened (ADR-0067)
 * @param closedByClerkId clerk who confirmed the close (null until closed)
 * @param openedAt when the session opened
 * @param closingStartedAt when begin-close recorded the count (null until then)
 * @param closedAt when the session reached CLOSED (null until then)
 * @param openedByName the opener's display name, "First Last"; null when it can't be resolved (#2608)
 * @param closedByName the closer's display name; null until closed or when it can't be resolved (#2608)
 */
public record RegisterSessionSummary(
        @NonNull UUID sessionId,
        @NonNull String terminalId,
        @Nullable UUID locationId,
        @NonNull String openedByClerkId,
        @NonNull String status,
        @NonNull BigDecimal openingFloat,
        @Nullable BigDecimal countedCash,
        @Nullable BigDecimal theoreticalCash,
        @Nullable BigDecimal overShort,
        boolean varianceApproved,
        @NonNull String currencyCode,
        @Nullable String closedByClerkId,
        @NonNull Instant openedAt,
        @Nullable Instant closingStartedAt,
        @Nullable Instant closedAt,
        @Nullable String openedByName,
        @Nullable String closedByName,
        @Nullable BigDecimal cashIncrement) {

    /** Never prints the actors' display names (ADR-0072). */
    @Override
    public String toString() {
        return "RegisterSessionSummary[sessionId=" + sessionId + ", terminalId=" + terminalId + ", locationId="
                + locationId + ", openedByClerkId=" + openedByClerkId + ", status=" + status + ", currencyCode="
                + currencyCode + ", closedByClerkId=" + closedByClerkId + "]";
    }
}
