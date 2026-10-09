package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import com.positivity.domainevents.order.RegisterSessionClosedV1.Movement;
import com.positivity.domainevents.order.RegisterSessionClosedV1.StatedTax;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Decides, per stated regime, how much of a petty expense's stated tax is recovered at close (CAP:550 S32d item 9;
 * AW52). Every recovered amount is a copied, stated figure times a configured share; nothing is calculated from a
 * rate, and nothing is estimated.
 *
 * <p>A regime {@code R} is recovered only when all of these hold on the movement's date, the calendar date of its
 * {@code occurredAt} in the tenant's accounting zone:
 *
 * <ol>
 *   <li>recovery under {@code R} is on ({@link InputTaxRecoveryFlags}), else {@code NOT_REGISTERED};
 *   <li>the category was recoverable when the movement was recorded, at the share then in force, read from
 *       accounting's own history and never from pos-order's copy, else {@code CATEGORY_NOT_RECOVERABLE};
 *   <li>pos-tax found the stated amounts {@code PLAUSIBLE}; null (not checked) or {@code RATE_UNAVAILABLE} is {@code
 *       RATE_UNAVAILABLE};
 *   <li>a supplier name and a receipt reference are present, else {@code EVIDENCE_MISSING};
 *   <li>when the evidence rule asked for the supplier's number, one is present, else {@code
 *       SUPPLIER_REGISTRATION_MISSING}.
 * </ol>
 *
 * <p>{@code rec_R = a_R × p / 100}, HALF_UP at the currency's exponent (ADR-0067 PC-6, OP-11). Under-claiming is
 * safe. A flag that cannot be obtained throws {@link TaxServiceUnavailableException}: the session rolls back for
 * retry, and recovery is never read as off.
 */
@Component
@RequiredArgsConstructor
public class PettyExpenseRecoveryDecider {

    public static final String NOT_REGISTERED = "NOT_REGISTERED";
    public static final String CATEGORY_NOT_RECOVERABLE = "CATEGORY_NOT_RECOVERABLE";
    public static final String RATE_UNAVAILABLE = "RATE_UNAVAILABLE";
    public static final String EVIDENCE_MISSING = "EVIDENCE_MISSING";
    public static final String SUPPLIER_REGISTRATION_MISSING = "SUPPLIER_REGISTRATION_MISSING";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final InputTaxRecoveryFlags flags;
    private final InputTaxRecoveryService settings;
    private final AccountingCalendarZoneResolver zoneResolver;

    /**
     * One decision per stated regime, in the order stated; empty for a movement without stated tax (a null list is a
     * message produced before S32d, read as empty).
     *
     * @throws TaxServiceUnavailableException when a flag cannot be obtained
     */
    public @NonNull List<RegimeRecovery> decide(@NonNull Movement movement) {
        List<StatedTax> stated = movement.statedTaxes() == null ? List.of() : movement.statedTaxes();
        if (stated.isEmpty()) {
            return List.of();
        }
        LocalDate date = zoneResolver.postingDate(movement.occurredAt());
        Optional<BigDecimal> share = movement.categoryCode() == null
                ? Optional.empty()
                : settings.shareInForce(movement.categoryCode(), movement.occurredAt());
        int exponent = Math.max(0, Currency.getInstance(movement.currencyCode()).getDefaultFractionDigits());
        List<RegimeRecovery> decisions = new ArrayList<>();
        for (StatedTax tax : stated) {
            String withheld = withheldReason(movement, tax.regime(), date, share);
            if (withheld != null) {
                decisions.add(new RegimeRecovery(
                        tax.regime(), tax.amount(), share.orElse(null), BigDecimal.ZERO.setScale(exponent), withheld));
            } else {
                BigDecimal recovered =
                        tax.amount().multiply(share.get()).divide(HUNDRED).setScale(exponent, RoundingMode.HALF_UP);
                decisions.add(new RegimeRecovery(tax.regime(), tax.amount(), share.get(), recovered, null));
            }
        }
        return List.copyOf(decisions);
    }

    private @Nullable String withheldReason(
            Movement movement, String regime, LocalDate date, Optional<BigDecimal> share) {
        if (!flags.inputTaxRecovery(date, regime)) {
            return NOT_REGISTERED;
        }
        if (share.isEmpty()) {
            return CATEGORY_NOT_RECOVERABLE;
        }
        if (!Movement.PLAUSIBLE.equals(movement.taxPlausibility())) {
            return RATE_UNAVAILABLE;
        }
        if (isBlank(movement.supplierName()) || isBlank(movement.receiptReference())) {
            return EVIDENCE_MISSING;
        }
        if (Boolean.TRUE.equals(movement.supplierRegistrationRequired())
                && isBlank(movement.supplierRegistrationNumber())) {
            return SUPPLIER_REGISTRATION_MISSING;
        }
        return null;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    /**
     * What one stated regime recovers.
     *
     * @param regime the regime
     * @param stated the amount the receipt states
     * @param percent the category's share in force when the movement was recorded; null when it was not recoverable
     * @param recovered the amount recovered, at the currency's exponent; zero when withheld
     * @param withheldReason why nothing is recovered; null when something is
     */
    public record RegimeRecovery(
            @NonNull String regime,
            @NonNull BigDecimal stated,
            @Nullable BigDecimal percent,
            @NonNull BigDecimal recovered,
            @Nullable String withheldReason) {}
}
