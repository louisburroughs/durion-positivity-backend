package com.positivity.customer.internal.entity;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Enumeration of contact roles within a customer account.
 *
 * <p>
 * Based on issue #108, these roles define different responsibilities and
 * permissions
 * that contacts can have within an organization's account.
 * </p>
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/108">Backend
 *      Issue #108</a>
 */
@Schema(description = "Contact role types for party relationships")
public enum ContactRole {

    /**
     * Contact authorized to receive and approve billing information.
     * At least one billing contact is required per account.
     */
    BILLING("Billing Contact", "Authorized to receive and approve billing information"),

    /**
     * Contact authorized to approve payment transactions.
     */
    PAYMENT_AUTHORIZER("Payment Authorizer", "Authorized to approve payment transactions"),

    /**
     * Contact responsible for operational matters.
     */
    OPERATIONS("Operations Contact", "Responsible for operational matters"),

    /**
     * Primary business contact for the account.
     */
    PRIMARY_BUSINESS_CONTACT("Primary Business Contact", "Primary business contact for the account"),

    /**
     * Technical contact for technical matters and integrations.
     */
    TECHNICAL("Technical Contact", "Contact for technical matters and integrations");

    private final String label;
    private final String description;

    ContactRole(String label, String description) {
        this.label = label;
        this.description = description;
    }

    /**
     * Get the human-readable label for this role.
     *
     * @return the display label
     */
    public String getLabel() {
        return label;
    }

    /**
     * Get the description of this role's responsibilities.
     *
     * @return the role description
     */
    public String getDescription() {
        return description;
    }
}
