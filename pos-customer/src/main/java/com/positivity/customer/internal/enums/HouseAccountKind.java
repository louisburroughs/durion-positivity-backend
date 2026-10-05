package com.positivity.customer.internal.enums;

/**
 * Marks a commercial party as a system house account (CAP:550 S7, #2505).
 *
 * <p>A house account is created by {@code HouseAccountProvisioner}, never by a request, and no
 * pos-customer write may change, merge, delete or attach data to it. Other modules recognise one
 * from the {@code houseAccount} value on the {@code customer.party.updated} fact — never from a
 * name or a customer number.
 */
public enum HouseAccountKind {
    /**
     * The tenant's CASH (walk-in) account: the registered customer a walk-in sale paid in full is
     * recorded against (decision AW12). Exactly one per tenant.
     */
    CASH_SALE
}
