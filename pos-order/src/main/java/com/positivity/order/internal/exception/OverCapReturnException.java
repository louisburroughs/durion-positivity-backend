package com.positivity.order.internal.exception;

import java.util.List;
import java.util.UUID;

/**
 * A requested return exceeded the un-refunded remainder of one or more sold lines (parity story F1,
 * spec R5.2). Maps to a 422 {@code RETURN_OVER_CAP} ApiError whose field errors list the current
 * {@code returnableQty} per offending line so the caller can correct and retry.
 */
public class OverCapReturnException extends RuntimeException {

    /**
     * @param orderLineId the sold line the over-cap request targeted
     * @param requested the requested returnQty
     * @param returnableQty the currently returnable remainder
     */
    public record LineCap(UUID orderLineId, int requested, int returnableQty) {}

    private final transient List<LineCap> offendingLines;

    public OverCapReturnException(List<LineCap> offendingLines) {
        super("Return exceeds the returnable quantity on one or more lines");
        this.offendingLines = List.copyOf(offendingLines);
    }

    public List<LineCap> getOffendingLines() {
        return offendingLines;
    }
}
