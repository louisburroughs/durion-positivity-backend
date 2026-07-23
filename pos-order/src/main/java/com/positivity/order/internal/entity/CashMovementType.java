package com.positivity.order.internal.entity;

/**
 * Direction of a non-sale drawer cash movement (odoo-parity story G1, spec R6.6; Odoo
 * {@code try_cash_in_out} analog). {@code PAID_IN} adds cash to the drawer, {@code PAID_OUT}
 * removes it; both are signed into the theoretical-cash total at close.
 */
public enum CashMovementType {
    PAID_IN,
    PAID_OUT
}
