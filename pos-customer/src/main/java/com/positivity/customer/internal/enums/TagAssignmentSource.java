package com.positivity.customer.internal.enums;

/**
 * How a {@code PartyTagAssignment} came to exist. Kept on the assignment so a
 * bulk import or campaign-driven tag can be distinguished from a CSR's manual
 * classification when the tag is later reviewed or revoked.
 */
public enum TagAssignmentSource {
    MANUAL,
    CAMPAIGN,
    IMPORT,
    RULE
}
