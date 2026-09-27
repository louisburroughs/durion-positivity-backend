package com.positivity.shopmanager.internal.service;

import org.springframework.stereotype.Service;

/**
 * See {@link RescheduleApprovalGuard}. Enforcement is entirely in the interface's {@code
 * @PreAuthorize}; reaching this method body means the caller already holds
 * {@code appointments:reschedule:approve}.
 */
@Service
public class RescheduleApprovalGuardImpl implements RescheduleApprovalGuard {

    @Override
    public void requireApprovalPermission() {
        // Intentionally empty — see class javadoc.
    }
}
