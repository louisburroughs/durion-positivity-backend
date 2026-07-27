package com.positivity.inventory.internal.enums;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The computed resolution strategies offered for an inventory shortage (odoo-parity G2, issue
 * #1049; plan decision D-5). Replaces the pre-G2 static {@code BACKORDER}/{@code CANCEL} pair —
 * each option is now computed from live building blocks (G1 backorders, X1 substitution replica,
 * C1 transfers, F4 purchase suggestions) and carries an expected-resolution date plus a cost delta
 * where computable.
 */
@Schema(description = "A computed strategy for resolving an inventory shortage")
public enum ShortageResolutionOption {

    /** Hold the demand open on a G1 backorder until availability covers it. */
    @Schema(description = "Place the short demand on a backorder (odoo-parity G1)")
    BACKORDER,

    /** Reserve a substitution-group member (X1 replica) that has live ATP at the site. */
    @Schema(description = "Substitute a group member with live availability (odoo-parity X1)")
    SUBSTITUTE,

    /** Pull surplus from a sibling site via a C1 cross-site transfer order. */
    @Schema(description = "Transfer surplus in from a sibling site (odoo-parity C1)")
    TRANSFER_IN,

    /** Pre-fill an F4 purchase suggestion to buy the shortfall. */
    @Schema(description = "Raise an emergency purchase suggestion (odoo-parity F4)")
    EMERGENCY_PURCHASE,

    /** Cancel the short line, releasing its reservation. */
    @Schema(description = "Cancel the short line and release its reservation")
    CANCEL_LINE
}
