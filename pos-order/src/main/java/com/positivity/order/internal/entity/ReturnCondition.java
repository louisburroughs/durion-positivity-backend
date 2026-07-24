package com.positivity.order.internal.entity;

/**
 * Disposition of a returned line (odoo-parity story F1, issue #1086, spec R5.1). {@code RESTOCK}
 * puts the item back into sellable inventory (the saga posts a return receipt); {@code SCRAP}
 * writes it off (no inventory movement); {@code WARRANTY} routes to pos-warranty rather than a
 * counter refund and is only valid on a returnable line.
 */
public enum ReturnCondition {
    RESTOCK,
    SCRAP,
    WARRANTY
}
