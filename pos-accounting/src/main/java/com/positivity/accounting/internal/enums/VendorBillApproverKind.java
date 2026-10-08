package com.positivity.accounting.internal.enums;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Who approved a vendor bill (CAP:550 S13, #2510; SPEC-accounting-workspace §4.3, §7.1 "System approver"): a person,
 * or the system on a HIGH match within the automatic limit. Only a person's approval can block the same person's
 * payment (separation of duties 2).
 */
@Schema(name = "VendorBillApproverKind", description = "Who approved the bill: a PERSON, or the SYSTEM automatically")
public enum VendorBillApproverKind {
    /** A person approved the bill, or accepted its match exception. */
    PERSON,
    /** The system approved the bill on a HIGH match within the automatic limit (AW37). */
    SYSTEM
}
