package com.positivity.tax.internal.enums;

/**
 * Lifecycle status of an exemption certificate (story T3).
 * <p>
 * Only {@link #ACTIVE} certificates (and only within their effective/expiry window)
 * drive an honored exemption; any other status causes a claimed exemption to be denied
 * and the line taxed anyway (decision D-T2).
 */
public enum ExemptionCertificateStatus {
    /** Certificate is valid and may drive exemptions within its date window. */
    ACTIVE,
    /** Certificate has been administratively deactivated. */
    INACTIVE,
    /** Certificate has been revoked and must never drive an exemption. */
    REVOKED
}
