package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.entity.ExtProductUomReplica;
import com.positivity.workorder.internal.exception.FractionalQuantityNotAllowedException;
import com.positivity.workorder.internal.exception.UomConversionUndefinedException;
import com.positivity.workorder.internal.repository.ExtProductUomReplicaRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Answers "may this part quantity carry decimals, and how many" from the catalog's own declaration
 * (ADR-0055, #1413).
 *
 * <h2>The rule is per-product, not per-platform</h2>
 *
 * A product's stock divisibility is the {@code precision_scale} of its {@code BASE} row in
 * {@code product_uom}, owned by pos-catalog and replicated here as {@code ext_product_uom}. Scale 0
 * means whole units; a non-zero scale means the quantity may carry that many decimal places and no
 * more.
 *
 * <p>Deliberately <em>not</em> "an inventory-tracked part is always whole". That statement is true
 * of every SKU seeded today, and encoding it would make bulk-dispensed stock — fluids by volume,
 * refrigerant by weight, hose by length, all on the roadmap and one already in the catalog — a
 * reversal across four modules rather than a data change on one product.
 *
 * <h2>Undeclared means whole</h2>
 *
 * A product with no unit-of-measure rows resolves to scale 0. That is every product right now:
 * {@code product_uom} is unpopulated platform-wide, the conversion subsystem is built and empty. So
 * the absent-row path is the common path, and it must stay the cheap one — a single indexed read
 * that finds nothing and answers zero. Defaulting to whole units is also what preserves today's
 * behaviour exactly while the declaration is installed ahead of any seeding.
 *
 * <h2>What is exempt</h2>
 *
 * A part carrying no {@code productEntityId} is not a catalog product — labour, shop supplies, a
 * non-stocked consumable. There is no declaration to read and nothing to enforce, so it keeps
 * accepting fractional quantities exactly as it does today.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartQuantityDivisibilityService {

    /** Used when a part line has no description to name in the error. */
    private static final String UNNAMED_PART = "this part";

    private final ExtProductUomReplicaRepository productUomReplicaRepository;

    /**
     * Rejects a quantity the referenced product's declared scale does not permit.
     *
     * <p>Equivalent to {@link #requirePermittedScale(UUID, String, BigDecimal, String)} with a
     * {@code null} {@code uomCode} — the quantity is already in the product's base unit.
     *
     * @param productEntityId the catalog product the part line references; {@code null} for a
     *     non-catalog line, which is exempt
     * @param partLabel the part's description, used to name it in the error
     * @param quantity the requested quantity, in the product's base unit
     * @throws FractionalQuantityNotAllowedException when the quantity carries more decimal places
     *     than the product declares
     */
    public void requirePermittedScale(
            @Nullable UUID productEntityId, @Nullable String partLabel, @NonNull BigDecimal quantity) {
        requirePermittedScale(productEntityId, partLabel, quantity, null);
    }

    /**
     * Rejects a quantity the referenced product's declared scale does not permit, converting from
     * {@code uomCode} to the product's base unit first when one is given (ADR-0055 stage 3,
     * #1415).
     *
     * <p>Conversion happens before the check, never after: rounding the quantity to fit and then
     * checking the rounded value would silently accept exactly the fraction ADR-0055 exists to
     * reject. The unrounded product of {@code quantity} and the replica's {@code factorToBase} is
     * what gets tested against the declared scale, and — on rejection — what names the offending
     * value in the error, so the counter sees the base-unit number the ledger would actually have
     * to hold.
     *
     * @param productEntityId the catalog product the part line references; {@code null} for a
     *     non-catalog line, which is exempt regardless of {@code uomCode}
     * @param partLabel the part's description, used to name it in the error
     * @param quantity the requested quantity, in {@code uomCode} when given, otherwise already in
     *     the product's base unit
     * @param uomCode the unit {@code quantity} is expressed in; {@code null} means the product's
     *     base unit — today's implicit behavior, unchanged
     * @throws FractionalQuantityNotAllowedException when the (converted) quantity carries more
     *     decimal places than the product declares
     * @throws UomConversionUndefinedException when {@code uomCode} is given and the product has no
     *     conversion row for it
     */
    public void requirePermittedScale(
            @Nullable UUID productEntityId,
            @Nullable String partLabel,
            @NonNull BigDecimal quantity,
            @Nullable String uomCode) {
        if (productEntityId == null) {
            return;
        }
        BigDecimal baseQuantity = uomCode == null || uomCode.isBlank()
                ? quantity
                : convertToBaseUnrounded(productEntityId, uomCode, quantity);

        int declaredScale = declaredScaleFor(productEntityId);
        if (fitsScale(baseQuantity, declaredScale)) {
            return;
        }

        String label = partLabel == null || partLabel.isBlank() ? UNNAMED_PART : partLabel;
        // Rounding up, not to nearest: an opened container is spent whether half of it was used or
        // all of it, so the whole one is what gets issued and billed (ADR-0055).
        BigDecimal suggestion = baseQuantity.setScale(declaredScale, RoundingMode.CEILING);
        throw declaredScale == 0
                ? FractionalQuantityNotAllowedException.wholeUnitsOnly(label, baseQuantity, suggestion)
                : FractionalQuantityNotAllowedException.scaleExceeded(label, baseQuantity, declaredScale, suggestion);
    }

    /**
     * {@code quantity} converted from {@code uomCode} to the product's base unit, exact and
     * unrounded — {@code BigDecimal.multiply} never loses precision, so this is safe to feed
     * straight into the scale check without pre-empting it.
     *
     * <p>Deliberately not the two-rounding-mode ({@code HALF_UP}/{@code DOWN}) conversion
     * pos-inventory's {@code UomConversionServiceImpl} performs at actual posting time — this
     * module never posts a ledger entry or promotes a reservation itself, so there is no rounded
     * value for it to own. What it owns is the pre-check: whether the exact converted quantity is
     * one the product's declaration would accept at all, mirrored from pos-inventory's semantics
     * because the module wall (ArchUnit) forbids importing its conversion service directly.
     */
    private BigDecimal convertToBaseUnrounded(UUID productEntityId, String uomCode, BigDecimal quantity) {
        ExtProductUomReplica row = productUomReplicaRepository
                .findByProductIdAndUomCode(productEntityId, uomCode)
                .orElseThrow(() -> UomConversionUndefinedException.noConversionRow(productEntityId, uomCode));
        return quantity.multiply(row.getFactorToBase());
    }

    /**
     * The product's declared base scale, or {@code 0} when it declares none.
     *
     * <p>Takes the largest scale on the unlikely chance a product carries more than one
     * {@code BASE} row: the key permits one row per UoM code, not one per type, so two are possible
     * as a seeding defect. Blocking every part entry for that product until the data is fixed would
     * punish the shop for a catalog error, and the widest declared scale is the reading that keeps
     * work flowing while rejecting anything no declaration supports.
     */
    public int declaredScaleFor(@NonNull UUID productEntityId) {
        List<Integer> scales = productUomReplicaRepository.findBasePrecisionScales(productEntityId);
        if (scales.size() > 1) {
            log.warn(
                    "Product {} declares {} BASE unit-of-measure rows; using the widest scale",
                    productEntityId,
                    scales.size());
        }
        return scales.stream().max(Integer::compareTo).orElse(0);
    }

    /**
     * Whether the value can be written at the given scale without losing anything.
     * {@code stripTrailingZeros} first, so {@code 1.00} is accepted against scale 0 — the trailing
     * zeros are how a client serialised the number, not a claim about divisibility.
     */
    private static boolean fitsScale(BigDecimal quantity, int scale) {
        return quantity.stripTrailingZeros().scale() <= scale;
    }
}
