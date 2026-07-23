package com.positivity.inventory.internal.exception;

import java.util.UUID;

/**
 * Thrown when a transfer-order endpoint references a site whose {@code LocationRef} status is
 * not eligible for stock movement (odoo-parity C1/C5, issue #1035; DECISION-INVENTORY-009:
 * INACTIVE and PENDING sites are blocked for movement).
 *
 * <p>Maps to a deterministic 422 with code {@link #ERROR_CODE}.
 */
public class TransferLocationNotEligibleException extends RuntimeException {

    /** Deterministic error code for the ApiError envelope. */
    public static final String ERROR_CODE = "TRANSFER_LOCATION_NOT_ELIGIBLE";

    public TransferLocationNotEligibleException(UUID locationId, String status, String role) {
        super("Site " + locationId + " (" + role + ") has status " + status
                + " and is not eligible for stock movement (DECISION-INVENTORY-009:"
                + " only ACTIVE sites may source or receive transfers)");
    }
}
