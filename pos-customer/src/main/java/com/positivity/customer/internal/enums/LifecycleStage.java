package com.positivity.customer.internal.enums;

/**
 * How far a party has progressed toward being a real customer (Story #1154).
 *
 * <p>Distinct from {@code AccountStatus}, which says whether a record is usable. A PROSPECT is
 * an active, valid record that has simply never bought anything — counting it as a customer
 * inflates every retention and churn figure the shop looks at.
 */
public enum LifecycleStage {
    /** Captured from an inquiry; no service history yet. */
    PROSPECT,
    ACTIVE,
    /** Was a customer, has gone quiet. */
    DORMANT
}
