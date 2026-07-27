package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.exception.AsOfInFutureException;
import com.positivity.inventory.internal.exception.InsufficientPermissionException;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Guard for point-in-time (as-of) inventory reads (odoo-parity A3, issue #1029).
 *
 * <p>As-of queries expose ledger history, not just current state, so on top of the endpoint's
 * static {@code inventory:on_hand:view} check they additionally require
 * {@value #LEDGER_VIEW_PERMISSION}. The same endpoint serves both modes, so this check is
 * programmatic (via {@link SecurityContextHelper}) rather than {@code @PreAuthorize} — the
 * pattern used for override permissions in {@code PutawayValidationServiceImpl}.
 */
@Component
@RequiredArgsConstructor
public class AsOfQueryGuard {

    public static final String LEDGER_VIEW_PERMISSION = "inventory:ledger:view";

    private final Clock clock;

    /**
     * Validates an as-of request: the caller must hold {@value #LEDGER_VIEW_PERMISSION}
     * (history exposure — 403 otherwise) and the instant must not be in the future (422).
     */
    public void check(@NonNull Instant asOf) {
        if (!SecurityContextHelper.hasAuthority(LEDGER_VIEW_PERMISSION)) {
            throw new InsufficientPermissionException(
                    SecurityContextHelper.getCurrentUsernameOrDefault("unknown"), LEDGER_VIEW_PERMISSION);
        }
        if (asOf.isAfter(Instant.now(clock))) {
            throw new AsOfInFutureException(asOf);
        }
    }
}
