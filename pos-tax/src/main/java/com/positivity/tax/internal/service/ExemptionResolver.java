package com.positivity.tax.internal.service;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxLineItem;
import com.positivity.tax.common.enums.ExemptionReasonCode;
import com.positivity.tax.internal.service.ActiveCertificateLookup.ActiveCertificate;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Classifies a line's exemption outcome for the calculate path (story T3, decision D-T2).
 * <p>
 * A line is a <em>certificate-backed exemption claim</em> when it carries an
 * {@code exemptionReasonCode} or {@code exemptionCertificateId}, or when the request
 * carries a transaction-level {@code customerExemption}. A claim is honored only if an
 * active, effective, non-expired certificate is found ({@link Outcome#EXEMPT}); otherwise
 * the line is taxed anyway and flagged ({@link Outcome#DENIED}) — a sale is never
 * hard-blocked. A bare {@code taxExempt=true} with no claim remains an intrinsic
 * non-taxable declaration ({@link Outcome#EXEMPT} with no reason), preserving prior
 * behavior. Everything else is {@link Outcome#TAXABLE}.
 */
@Slf4j
@Component
public class ExemptionResolver {

    private final ActiveCertificateLookup lookup;

    public ExemptionResolver(ActiveCertificateLookup lookup) {
        this.lookup = lookup;
    }

    /** Per-line exemption outcome. */
    public enum Outcome {
        /** Line is taxed normally. */
        TAXABLE,
        /** Line is exempt: zero-rate jurisdiction rows, excluded from the taxable base. */
        EXEMPT,
        /** Exemption claimed but unbacked by a valid certificate: taxed anyway and flagged. */
        DENIED
    }

    /**
     * Resolved classification for a single line.
     *
     * @param outcome the exemption outcome
     * @param reason  the resolved/claimed reason, or {@code null} for an intrinsic exemption or a taxable line
     */
    public record LineExemption(
            @NonNull Outcome outcome, @Nullable ExemptionReasonCode reason) {
        static LineExemption taxable() {
            return new LineExemption(Outcome.TAXABLE, null);
        }

        static LineExemption exempt(@Nullable ExemptionReasonCode reason) {
            return new LineExemption(Outcome.EXEMPT, reason);
        }

        static LineExemption denied(@Nullable ExemptionReasonCode reason) {
            return new LineExemption(Outcome.DENIED, reason);
        }
    }

    /**
     * Classify a line's exemption outcome.
     *
     * @param request    the calculation request (for the transaction-level exemption and customerId)
     * @param line       the line item
     * @param stateScope destination state scope used for certificate matching (may be {@code null})
     * @param date       the transaction date used for certificate validity
     * @return the resolved classification
     */
    @NonNull
    public LineExemption classify(
            @NonNull TaxCalculationRequest request,
            @NonNull TaxLineItem line,
            @Nullable String stateScope,
            @NonNull LocalDate date) {

        TaxCalculationRequest.CustomerExemption customerExemption = request.getCustomerExemption();
        ExemptionReasonCode claimedReason = firstNonNull(
                line.getExemptionReasonCode(), customerExemption == null ? null : customerExemption.getReasonCode());
        UUID claimedCertificateId = firstNonNull(
                line.getExemptionCertificateId(),
                customerExemption == null ? null : customerExemption.getCertificateId());

        boolean certificateBackedClaim =
                claimedReason != null || claimedCertificateId != null || customerExemption != null;

        if (!certificateBackedClaim) {
            // No certificate-backed claim: a bare taxExempt flag is an intrinsic non-taxable
            // declaration (reportable zero-tax); anything else is taxable.
            return line.isTaxExempt() ? LineExemption.exempt(null) : LineExemption.taxable();
        }

        Optional<ActiveCertificate> certificate =
                lookup.findActive(request.getCustomerId(), claimedCertificateId, stateScope, claimedReason, date);
        if (certificate.isPresent()) {
            ExemptionReasonCode resolvedReason = firstNonNull(certificate.get().reasonCode(), claimedReason);
            return LineExemption.exempt(resolvedReason);
        }

        log.debug(
                "Exemption denied for line {} (customerId present={}, reason={}): no active certificate on {}",
                line.getLineItemId(),
                request.getCustomerId() != null,
                claimedReason,
                date);
        return LineExemption.denied(claimedReason);
    }

    @Nullable
    private static <T> T firstNonNull(@Nullable T a, @Nullable T b) {
        return a != null ? a : b;
    }
}
