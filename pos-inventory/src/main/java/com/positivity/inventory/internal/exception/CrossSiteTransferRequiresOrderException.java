package com.positivity.inventory.internal.exception;

import java.util.UUID;

/**
 * Thrown when {@code POST /v1/inventory/stock-movements} receives a TRANSFER whose from/to
 * locations resolve to DIFFERENT sites (odoo-parity C2/spec C7, issue #1036): the immediate
 * paired TRANSFER_OUT/TRANSFER_IN path is retained for intra-site bin moves only — cross-site
 * moves must go through a transfer order so in-transit stock is represented.
 *
 * <p>Maps to a deterministic 422 with code {@link #ERROR_CODE}.
 */
public class CrossSiteTransferRequiresOrderException extends RuntimeException {

    /** Deterministic error code for the ApiError envelope. */
    public static final String ERROR_CODE = "CROSS_SITE_TRANSFER_REQUIRES_ORDER";

    public CrossSiteTransferRequiresOrderException(UUID fromLocationId, UUID fromSite, UUID toLocationId, UUID toSite) {
        super("TRANSFER from " + fromLocationId + " (site " + fromSite + ") to " + toLocationId + " (site " + toSite
                + ") crosses sites; immediate stock movements are intra-site bin moves only —"
                + " create a transfer order via POST /v1/inventory/transfer-orders instead");
    }
}
