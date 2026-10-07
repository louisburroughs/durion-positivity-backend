package com.positivity.order.internal.client;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * pos-security-service's step-up credential check (CAP:550 S16, #2512; AW31): verifies a person's own
 * credentials once, in the caller's tenant, under the sign-in lockout policy, without issuing a token
 * or opening a session.
 */
public interface StepUpPort {

    /**
     * Verifies {@code username} / {@code password} and asks whether that person holds {@code permission},
     * and with what location scope.
     *
     * @param locationId the location the approved action is at, passed for the audit log
     * @return the verified person, whether they hold the permission, and that grant's location scope
     * @throws com.positivity.order.internal.exception.CashMovementRefusedException with {@code
     *     APPROVAL_DENIED} when the check refuses (403 {@code STEP_UP_DENIED}), for any reason
     * @throws com.positivity.order.internal.exception.StepUpUnavailableException when the check could
     *     not be made, or answered anything other than a result or that refusal
     */
    @NonNull
    StepUpResult verify(
            @NonNull String username, @NonNull String password, @NonNull String permission, @Nullable UUID locationId);

    /**
     * The verified person and the grant asked about.
     *
     * @param userId the verified person
     * @param holdsPermission whether their effective roles grant the permission
     * @param financialScoped whether the grant is location-scoped on the FINANCIAL dimension
     * @param otherScoped whether the grant is location-scoped on the OTHER dimension
     * @param assignedLocationIds their assigned location nodes; empty when the grant is global, and
     *     when it is scoped with no assignment (reaches nowhere)
     */
    record StepUpResult(
            @NonNull UUID userId,
            boolean holdsPermission,
            boolean financialScoped,
            boolean otherScoped,
            @NonNull List<UUID> assignedLocationIds) {

        public StepUpResult {
            assignedLocationIds = assignedLocationIds == null ? List.of() : List.copyOf(assignedLocationIds);
        }
    }
}
