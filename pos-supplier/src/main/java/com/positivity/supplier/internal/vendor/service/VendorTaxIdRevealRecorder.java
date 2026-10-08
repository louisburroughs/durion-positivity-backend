package com.positivity.supplier.internal.vendor.service;

import com.positivity.security.common.SecurityContextHelper;
import com.positivity.supplier.internal.audit.SupplierCorrelationContext;
import com.positivity.supplier.internal.entity.SupplierVendorTaxIdRevealEntity;
import com.positivity.supplier.internal.entity.VendorTaxRegistration;
import com.positivity.supplier.internal.enums.TaxIdRevealOutcome;
import com.positivity.supplier.internal.repository.SupplierVendorTaxIdRevealRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the audit row of a vendor tax-registration reveal (#2621, Security ruling on #2617, ruling 4), in the
 * reveal's own transaction. It FAILS CLOSED, as {@code AuditAccessRecorder} does for exchange payloads: the
 * row is a precondition of the reveal, so a failure here propagates and the caller gets no number.
 *
 * <p>{@code MANDATORY}, never {@code REQUIRES_NEW}: in its own transaction the row could commit while the
 * reveal fails, or the reveal could succeed while the row rolled back on its own, and "recorded if and only if
 * revealed" would be lost. {@code saveAndFlush} surfaces a failed insert now, before a number is handed to the
 * serializer.
 *
 * <p>The row holds the actor and roles from the security context (ADR-0018; pos-supplier stores no
 * {@code personId}, so ADR-0022's claim is not recorded here), the reason, the request's correlation id and
 * the outcome. Never the number and never {@code last4}.
 */
@Component
@RequiredArgsConstructor
public class VendorTaxIdRevealRecorder {

    private static final String ROLE_PREFIX = "ROLE_";

    private final SupplierVendorTaxIdRevealRepository revealRepository;
    private final Clock clock;

    /**
     * Records one reveal of {@code registration} of {@code vendorId}. Deliberately catches nothing.
     *
     * @param outcome {@code REVEALED}, or {@code UNREADABLE} when decryption failed and nothing is returned
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
            @NonNull UUID vendorId,
            @NonNull VendorTaxRegistration registration,
            @NonNull String reason,
            @NonNull TaxIdRevealOutcome outcome) {
        Objects.requireNonNull(vendorId, "vendorId must not be null");
        Objects.requireNonNull(registration, "registration must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        SupplierVendorTaxIdRevealEntity row = SupplierVendorTaxIdRevealEntity.builder()
                .vendorId(vendorId)
                .registrationId(registration.registrationId())
                .scheme(registration.scheme())
                // From the security context, never a parameter: an actor a caller could supply is a claim.
                .revealedBy(SecurityContextHelper.getCurrentUsernameOrDefault("unknown"))
                .revealedByRoles(truncate(currentRoles(), 1000))
                .reason(reason)
                // Truncated to the column: the value is caller-influenced (the inbound X-Correlation-Id), and an
                // oversized header must not be able to deny a reveal by failing this insert.
                .correlationId(truncate(SupplierCorrelationContext.currentOrGenerate(), 100))
                .revealedAt(Instant.now(clock))
                .outcome(outcome)
                .build();
        revealRepository.saveAndFlush(row);
    }

    /** The subject's roles without the {@code ROLE_} prefix, sorted and comma-separated. */
    @NonNull
    static String currentRoles() {
        if (!SecurityContextHelper.isAuthenticated()) {
            return "";
        }
        return SecurityContextHelper.getAuthorities().stream()
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .map(authority -> authority.substring(ROLE_PREFIX.length()))
                .sorted()
                .collect(Collectors.joining(","));
    }

    @NonNull
    private static String truncate(@NonNull String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
