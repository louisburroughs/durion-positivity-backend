package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.exception.FractionalQuantityNotAllowedException;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The supply side of the per-product divisibility invariant (ADR-0055, #1414).
 *
 * <h2>What this replaces, and what it keeps</h2>
 *
 * Receiving, ASN and returns used to call {@code intValueExact()} on the base quantity and throw
 * on any fraction. That was correct for the entire catalog as seeded — every SKU is integral — and
 * it was the right instinct: a ledger that silently rounds is worse than one that refuses. What
 * was wrong is that the rule was a constant rather than a reading of the catalog's own
 * declaration, so the first product to declare {@code precision_scale > 0} would have been refused
 * by the ledger while the conversion subsystem happily produced its fractional base quantity.
 *
 * <p>So the guards keep their fail-closed character and lose their hardcoding: the quantity is
 * checked against the scale the product declares, and a product declaring nothing — which is every
 * product until one is seeded otherwise — is refused a fraction exactly as before.
 *
 * <h2>Symmetry with the demand side</h2>
 *
 * pos-workorder enforces the same rule at estimate-item entry and part issue (#1413) and raises
 * {@code FRACTIONAL_QUANTITY_NOT_ALLOWED}. This raises the same code from the same declaration, so
 * both sides of the ledger enforce one invariant rather than two that happen to agree.
 */
@Service
@RequiredArgsConstructor
public class QuantityScaleGuard {

    private final UomConversionService uomConversionService;

    /**
     * Returns the quantity when the product's declaration permits it; throws otherwise.
     *
     * @param productId the catalog product the posting is for; {@code null} when the stock
     *     reference resolves to no catalog product, which declares nothing and so is integral
     * @param stockReference the SKU or stock-item id, used to name the product in the error
     * @param fieldName the request field the quantity came from
     * @param quantity the base-UoM quantity about to be posted; {@code null} — a request that
     *     omitted the field — is rejected here rather than at the first arithmetic on it
     * @throws FractionalQuantityNotAllowedException when the quantity carries more decimal places
     *     than the product declares
     */
    public @NonNull BigDecimal requirePostable(
            @Nullable UUID productId,
            @NonNull String stockReference,
            @NonNull String fieldName,
            @Nullable BigDecimal quantity) {
        if (quantity == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        int declaredScale = uomConversionService.declaredBaseScale(productId);
        if (fitsScale(quantity, declaredScale)) {
            return quantity;
        }
        throw declaredScale == 0
                ? FractionalQuantityNotAllowedException.wholeUnitsOnly(productId, stockReference, fieldName, quantity)
                : FractionalQuantityNotAllowedException.scaleExceeded(
                        productId, stockReference, fieldName, quantity, declaredScale);
    }

    /**
     * The read-path counterpart: a stored quantity whose scale the product does not declare is a
     * ledger-integrity failure, and it is reported as one.
     *
     * <p>This replaces the {@code Math.toIntExact} calls the availability computation used to
     * narrow its {@code long} math with. Those threw rather than wrapping, and that fail-loud
     * property is the one thing about them worth preserving: an availability figure that silently
     * lost information is worse than no figure at all. What they can no longer do is bound the
     * value, because a decimal column has no 32-bit edge to fall off — so the check they are
     * replaced by is the one that still means something after the widening: does this quantity
     * carry more precision than the product ever declared it could?
     *
     * <p>The check only runs when the stock reference names a catalog product: a reference that
     * names none declares nothing, and "declares nothing" cannot be evidence that a value already
     * in the ledger is wrong.
     *
     * <p>An {@link IllegalStateException} rather than the 422: nothing the caller sent is wrong.
     * A quantity in the ledger that no declaration supports means the posting path was bypassed or
     * a declaration was narrowed after the fact, and both are the platform's problem to fix. The
     * fact publisher already isolates a failed availability snapshot per key, so this surfaces as
     * a skipped fact and a log line rather than a rolled-back business write.
     */
    public @NonNull BigDecimal requireReportable(
            @Nullable UUID productId,
            @NonNull String stockReference,
            @NonNull String fieldName,
            @NonNull BigDecimal quantity) {
        if (productId == null) {
            // Nothing to check against. Unlike the posting side, where "no declaration" is a
            // decision the platform makes about what may be written and defaults to whole units,
            // here the quantity is already written — the ledger is the record. Refusing to report
            // a value we have no declaration to contradict would turn a read into a 500 and stall
            // the availability replicas behind it, and it would do so precisely for the stock
            // items the ledger keys by human SKU rather than product id (its two posting paths
            // disagree about which goes in `stockItemId`). Report what is there.
            return quantity;
        }
        int declaredScale = uomConversionService.declaredBaseScale(productId);
        if (fitsScale(quantity, declaredScale)) {
            return quantity;
        }
        throw new IllegalStateException("Ledger quantity " + quantity.toPlainString() + " for '" + stockReference
                + "' (" + fieldName + ") carries more decimal places than the product declares (precision_scale="
                + declaredScale + "); the posting path should have rejected it");
    }

    /**
     * The catalog product a ledger stock-item id refers to, or {@code null} when it names none.
     *
     * <p>pos-inventory's ledger stores {@code stockItemId} as a plain string and its own posting
     * paths disagree about what goes in it — the allocation path writes a product UUID, receiving
     * paths write the human SKU. A stock item that does not resolve to a catalog product declares
     * nothing, and undeclared means whole units.
     */
    public static @Nullable UUID productIdOf(@Nullable String stockItemId) {
        if (stockItemId == null || stockItemId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(stockItemId.trim());
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    /**
     * Whether a value can be written at the given scale without losing anything.
     *
     * <p>{@code stripTrailingZeros} first, so {@code 1.00} is accepted against scale 0: the
     * trailing zeros are how a client or a JDBC driver spelled the number, not a claim about
     * divisibility. Postgres returns {@code numeric(19,4)} at scale 4, so without this every value
     * read back out of the ledger would fail its own guard.
     */
    public static boolean fitsScale(@NonNull BigDecimal quantity, int scale) {
        return quantity.stripTrailingZeros().scale() <= scale;
    }
}
