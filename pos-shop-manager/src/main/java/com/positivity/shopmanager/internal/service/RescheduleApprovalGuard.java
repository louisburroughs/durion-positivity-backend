package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.security.ShopPermissions;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Gate for approving a reschedule beyond the free allowance (DECISION-SHOPMGMT-004): the caller's
 * 3rd or later reschedule of an appointment, when that reschedule is not shop-caused.
 *
 * <p>A separate, always-{@code @PreAuthorize}-gated bean, the same shape {@link
 * ConflictOverrideService} uses for {@code shop:conflict:override}: {@code
 * AppointmentsServiceImpl#rescheduleAppointment} calls {@link #requireApprovalPermission()} only
 * once it has determined approval is needed for this reschedule, so the permission is enforced by
 * the platform's ordinary {@code @PreAuthorize} wall — a denial throws the same {@code
 * AccessDeniedException} every other permission failure in this module throws, mapped to {@code
 * 403} by {@code pos-web-common}'s platform-wide handler — rather than a hand-rolled check that the
 * repo's RBAC tooling cannot see.
 */
public interface RescheduleApprovalGuard {

    @PreAuthorize("hasAuthority('" + ShopPermissions.APPOINTMENTS_RESCHEDULE_APPROVE + "')")
    void requireApprovalPermission();
}
