package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.exception.UomConversionUndefinedException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Document-boundary UoM conversion helper (odoo-parity B2, #1034, spec §3 B2–B4).
 *
 * <p>PO lines, ASN lines, receiving lines, and return lines may optionally carry a document UoM +
 * document quantity (e.g. {@code 1 CASE}). At posting/derivation time this component converts the
 * keyed document quantity into the product's base UoM via {@link UomConversionService} (HALF_UP at
 * the base UoM's precision scale — receipts are not reservations, so the DOWN variant does not
 * apply here) and returns the audit metadata persisted on the document line: the keyed values plus
 * the effective conversion factor actually applied (including rounding).
 *
 * <p>A document UoM with no conversion path — unknown product, unknown UoM, or a stock reference
 * that does not resolve to a catalog product id at all — raises
 * {@link UomConversionUndefinedException} (422 {@code UOM_CONVERSION_UNDEFINED}), never a silent
 * 1:1 assumption (spec B4).
 */
@Component
@RequiredArgsConstructor
public class DocumentQuantityConverter {

    /** Scale of the persisted effective conversion factor; matches {@code numeric(20,6)}. */
    private static final int FACTOR_SCALE = 6;

    private final UomConversionService uomConversionService;

    /**
     * Base quantity + audit metadata for one converted document line: {@code baseQuantity} is
     * what flows everywhere the line quantity flows (line totals excepted — money math stays in
     * document units), {@code documentUom}/{@code documentQuantity} are the values as keyed, and
     * {@code conversionFactor} is the effective base-per-document-unit factor applied.
     */
    public record DocumentConversion(
            @NonNull BigDecimal baseQuantity,
            @NonNull String documentUom,
            @NonNull BigDecimal documentQuantity,
            @NonNull BigDecimal conversionFactor) {}

    /**
     * Converts the optional document UoM pair to base, or returns empty when the line is keyed
     * directly in base UoM (both fields absent — pre-B2 behavior, byte-identical).
     *
     * @param productId catalog product id, or {@code null} when the line's stock reference does
     *     not resolve to one (raises {@code UOM_CONVERSION_UNDEFINED} if a document UoM is given)
     * @param stockReference the line's stock reference as keyed, for error messages
     * @throws IllegalArgumentException when only one of the pair is supplied, or the document
     *     quantity is not positive
     * @throws UomConversionUndefinedException when no conversion path to base exists
     */
    public Optional<DocumentConversion> convertIfPresent(
            @Nullable UUID productId,
            @Nullable String stockReference,
            @Nullable String documentUom,
            @Nullable BigDecimal documentQuantity) {
        if (documentUom == null && documentQuantity == null) {
            return Optional.empty();
        }
        if (documentUom == null || documentUom.isBlank() || documentQuantity == null) {
            throw new IllegalArgumentException("documentUom and documentQuantity must be provided together");
        }
        if (documentQuantity.signum() <= 0) {
            throw new IllegalArgumentException("documentQuantity must be positive");
        }
        if (productId == null) {
            throw UomConversionUndefinedException.unresolvableProduct(stockReference, documentUom);
        }
        BigDecimal baseQuantity = uomConversionService.toBaseQuantity(productId, documentUom, documentQuantity);
        BigDecimal conversionFactor = baseQuantity.divide(documentQuantity, FACTOR_SCALE, RoundingMode.HALF_UP);
        return Optional.of(new DocumentConversion(baseQuantity, documentUom, documentQuantity, conversionFactor));
    }
}
