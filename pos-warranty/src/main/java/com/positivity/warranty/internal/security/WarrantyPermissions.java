package com.positivity.warranty.internal.security;

/**
 * Permission constants for warranty operations (PRD-warranty-claims-module.md §9.1).
 * Names must match permissions.yaml and the gateway/security-service catalogs exactly.
 */
public final class WarrantyPermissions {

    public static final String PROVIDER_VIEW = "warranty:provider:view";
    public static final String PROVIDER_MANAGE = "warranty:provider:manage";
    public static final String POLICY_VIEW = "warranty:policy:view";
    public static final String POLICY_MANAGE = "warranty:policy:manage";
    public static final String REGISTRATION_VIEW = "warranty:registration:view";
    public static final String REGISTRATION_MANAGE = "warranty:registration:manage";
    public static final String CLAIM_VIEW = "warranty:claim:view";
    public static final String CLAIM_CREATE = "warranty:claim:create";
    public static final String CLAIM_SUBMIT = "warranty:claim:submit";
    public static final String CLAIM_DECIDE = "warranty:claim:decide";
    public static final String CLAIM_SETTLE = "warranty:claim:settle";
    public static final String CLAIM_CANCEL = "warranty:claim:cancel";
    public static final String CLAIM_CLOSE = "warranty:claim:close";
    public static final String REIMBURSEMENT_VIEW = "warranty:reimbursement:view";
    public static final String REIMBURSEMENT_MANAGE = "warranty:reimbursement:manage";
    public static final String PART_RETURN_VIEW = "warranty:part-return:view";
    public static final String PART_RETURN_MANAGE = "warranty:part-return:manage";

    private WarrantyPermissions() {}
}
