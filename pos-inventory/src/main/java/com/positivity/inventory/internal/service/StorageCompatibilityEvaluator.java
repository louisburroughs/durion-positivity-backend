package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.StorageCompatibility;
import com.positivity.inventory.internal.enums.StorageCompatibilityMatchLevel;
import com.positivity.inventory.internal.repository.StorageCompatibilityRepository;
import com.positivity.inventory.internal.service.SkuCategoryLookup.SkuCategoryRef;
import com.positivity.inventory.internal.service.StorageLocationValidationService.StorageLocationValidation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides whether an item may physically go in a destination (issue #1514).
 *
 * <p>This is the check that replaced the two replenishment-policy gates in
 * {@link PutawayValidationServiceImpl}. Those gates asked "is there an {@code (itemSKU, locationId)}
 * replenishment policy row", which is not a question about physical fitness at all: it meant a
 * brand-new SKU could never be put away anywhere, while a tire could be put away into oil storage as
 * long as somebody had written a policy row for it. Replenishment policies are slotting targets for
 * the restock scan; a bin's storage class is what says a tire belongs on a tire rack.
 *
 * <p>Order of judgement, and why:
 *
 * <ol>
 *   <li><b>{@code STAGING} / {@code QUARANTINE} refuse everything.</b> They are putaway
 *       <em>sources</em>, not destinations. Checked first so the operator gets that reason rather
 *       than a bare "no matching rule".
 *   <li><b>Containment is required wherever the item's own class demands it, whatever the
 *       destination is coded as.</b> When every storage class the matrix accepts for an item
 *       requires containment, containment is a property of the <em>item</em>, not of any one
 *       destination — a battery needs a contained rack no matter which bin it is offered. This gate
 *       runs before the {@code GENERAL} short-circuit below, and it is a deliberate, narrow
 *       tightening of the #1514 contract's "GENERAL accepts every category".
 *       <p>It is defence in depth rather than a workaround for missing data: the seeded storage
 *       locations <em>do</em> declare capabilities
 *       ({@code pos-location R__seed_location_2_operational_data.sql}, the #1514 section), so on a
 *       seeded environment a battery routes to a {@code BATTERY_RACK} by rule and never reaches
 *       this gate. What the gate covers is every path that yields {@code GENERAL} without anyone
 *       having judged the location fit for acid: a storage location created through the API without
 *       a capability, and a replica row not yet rehydrated after #1514 (null resolves permissively
 *       to {@code GENERAL} — see the next bullet). Silent acceptance is the wrong failure mode for
 *       hazardous goods, so the item's own requirement is enforced independently of how well the
 *       destination happens to be classified.
 *       <p>Items whose accepted set mixes contained and uncontained classes are unaffected:
 *       {@code Fluids & Chemicals} accepts {@code OIL_STORAGE} (contained) or {@code BULK_FLOOR}
 *       (not), so oil on a bulk floor stays legal. Only an item with no legal uncontained
 *       destination at all — a battery — is treated as carrying the requirement itself.
 *   <li><b>{@code GENERAL} accepts every category.</b> It is the permissive default per the #1514
 *       contract, and it is also where a null code lands: pos-location resolves an undeclared
 *       capability to {@code GENERAL} before publishing, so null on the replica means "no
 *       post-#1514 fact seen yet", not "undeclared". Resolving null permissively keeps a cold
 *       replica behaving as it did pre-#1514 instead of dead-ending every receipt — which is the
 *       class of bug #1514 exists to fix. There is deliberately no third "unknown" branch; it would
 *       be dead code. A destination absent from the replica altogether lands here too, which is
 *       why the containment gate above is not conditioned on the destination's code.
 *   <li><b>An item with no resolvable class is accepted only by {@code GENERAL}.</b> Having got
 *       past step 2, the destination declares a specific class, and there is nothing to match it
 *       against.
 *   <li><b>Subcategory rows override category rows entirely.</b> When any subcategory row exists,
 *       the parent's rows are ignored. This is the whole reason SUBCATEGORY outranks CATEGORY:
 *       {@code Batteries} is a subcategory of {@code Electrical System}, and a battery must not
 *       inherit its parent's {@code SMALL_PARTS_BIN} permission.
 *   <li><b>Containment is required where the matched row says so.</b> {@code BATTERY_RACK} and
 *       {@code OIL_STORAGE} are the containment-bearing classes; a destination coded as one but not
 *       declaring {@code hazard_containment} is refused. An explicit {@code TRUE} is required, so
 *       both null and false refuse.
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class StorageCompatibilityEvaluator {

    /** The permissive default. Also where a null replica code resolves; see the class javadoc. */
    static final String GENERAL = "GENERAL";

    /** Putaway sources, never destinations. */
    private static final List<String> SOURCE_ONLY_CATEGORIES = List.of("STAGING", "QUARANTINE");

    /**
     * Whether a storage class is a putaway <em>source</em> rather than a destination.
     *
     * <p>Exposed so destination <em>selection</em> can skip these before it ever proposes one.
     * {@link PutawayDestinationResolver}'s {@code CLOSEST_AVAILABLE} search ranks every active bin
     * at the site, and a staging floor or quarantine cage is active — so without this filter an
     * overflow hop can land on one, pass the capacity gate, and then be refused here, failing the
     * whole receipt on a destination the system chose for itself.
     *
     * <p>A null code is not source-only: it means no post-#1514 fact has been seen for that
     * location yet and resolves to {@code GENERAL}, per the class javadoc.
     */
    static boolean isSourceOnly(@Nullable String storageCategoryCode) {
        return storageCategoryCode != null && SOURCE_ONLY_CATEGORIES.contains(storageCategoryCode);
    }

    private final StorageCompatibilityRepository storageCompatibilityRepository;
    private final SkuCategoryLookup skuCategoryLookup;

    /**
     * Whether {@code skuId} may be put away into {@code destination}.
     *
     * @param destination the destination's replicated capability, from
     *     {@link StorageLocationValidationService}
     * @param skuId the item being put away
     * @return an accepting verdict, or a refusing one carrying the class/capability mismatch as
     *     operator-readable text for the {@code LOCATION_NOT_VALID_FOR_SKU} error
     */
    @Transactional(readOnly = true)
    public @NonNull Verdict evaluate(@NonNull StorageLocationValidation destination, @NonNull String skuId) {
        String destinationCategory = resolveCategoryCode(destination.getStorageCategoryCode());

        if (SOURCE_ONLY_CATEGORIES.contains(destinationCategory)) {
            return Verdict.refuse(destinationCategory
                    + " locations are putaway sources, not destinations, and accept no stored goods");
        }

        SkuCategoryRef ref = skuCategoryLookup.categoryRefOf(skuId).orElse(null);
        if (ref == null || ref.isEmpty()) {
            // An unclassified item is accepted only by GENERAL. It cannot be containment-bearing,
            // because there is no matrix row to say so.
            if (GENERAL.equals(destinationCategory)) {
                return Verdict.accept();
            }
            return Verdict.refuse(String.format(
                    "the item carries no catalog classification, and only GENERAL storage accepts an unclassified"
                            + " item — this destination is %s",
                    destinationCategory));
        }

        Governing governing = governingRows(ref);

        // Step 2 of the class javadoc: an item every one of whose accepted classes requires
        // containment carries that requirement itself, so it is checked before GENERAL can accept.
        if (requiresContainment(governing.rows()) && !Boolean.TRUE.equals(destination.getHazardContainment())) {
            return Verdict.refuse(String.format(
                    "catalog class %s may only be stored with hazard containment, and this destination (%s) does"
                            + " not declare it",
                    governing.label(), destinationCategory));
        }

        if (GENERAL.equals(destinationCategory)) {
            return Verdict.accept();
        }
        if (governing.rows().isEmpty()) {
            return Verdict.refuse(String.format(
                    "no storage compatibility is defined for catalog class %s, so %s cannot be shown to accept it",
                    governing.label(), destinationCategory));
        }

        Optional<StorageCompatibility> match = governing.rows().stream()
                .filter(row -> destinationCategory.equalsIgnoreCase(row.getStorageCategoryCode()))
                .findFirst();
        if (match.isEmpty()) {
            return Verdict.refuse(String.format(
                    "%s does not accept catalog class %s (accepted: %s)",
                    destinationCategory, governing.label(), acceptedCodes(governing.rows())));
        }

        if (match.get().isRequiresContainment() && !Boolean.TRUE.equals(destination.getHazardContainment())) {
            return Verdict.refuse(String.format(
                    "%s requires hazard containment for catalog class %s, and this destination does not declare it",
                    destinationCategory, governing.label()));
        }

        return Verdict.accept();
    }

    /**
     * Whether containment is a property of the item rather than of one destination: true when every
     * storage class the matrix accepts for it demands containment, so no uncontained destination can
     * ever be right. Batteries qualify (their only accepted class is {@code BATTERY_RACK});
     * {@code Fluids & Chemicals} do not, because {@code BULK_FLOOR} is an accepted uncontained
     * option alongside {@code OIL_STORAGE}.
     */
    private static boolean requiresContainment(List<StorageCompatibility> rows) {
        return !rows.isEmpty() && rows.stream().allMatch(StorageCompatibility::isRequiresContainment);
    }

    /**
     * The rows that govern this item, together with the label for the level they came from: the
     * subcategory's rows if it has any, otherwise the category's. Subcategory rows replace rather
     * than supplement — see step 4 of the class javadoc.
     *
     * <p>The label travels with the rows so a refusal names the level that actually decided. A tire
     * carries both a category and a subcategory but is governed by its category (there is no tire
     * override), and reporting "Passenger Car Tires" for a decision made on "Tires & Wheels" would
     * send an operator looking for a subcategory rule that does not exist.
     */
    private Governing governingRows(SkuCategoryRef ref) {
        if (ref.subcategoryId() != null) {
            List<StorageCompatibility> overrides = storageCompatibilityRepository.findByMatchLevelAndCatalogRefId(
                    StorageCompatibilityMatchLevel.SUBCATEGORY, ref.subcategoryId());
            if (!overrides.isEmpty()) {
                return new Governing(overrides, describe(ref.subcategoryName(), ref.subcategoryId()));
            }
        }
        UUID categoryId = ref.categoryId();
        String categoryLabel = describe(ref.categoryName(), categoryId);
        if (categoryId == null) {
            return new Governing(List.of(), categoryLabel);
        }
        return new Governing(
                storageCompatibilityRepository.findByMatchLevelAndCatalogRefId(
                        StorageCompatibilityMatchLevel.CATEGORY, categoryId),
                categoryLabel);
    }

    /** The rows that decided, and the catalog level they came from. */
    private record Governing(List<StorageCompatibility> rows, String label) {}

    /**
     * Null and blank both mean "no post-#1514 fact for this location", which resolves to the
     * permissive default rather than to a third unknown state.
     */
    private static String resolveCategoryCode(@Nullable String replicaCode) {
        if (replicaCode == null || replicaCode.isBlank()) {
            return GENERAL;
        }
        return replicaCode.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * A catalog class for the operator's benefit: the name snapshot when there is one, the id
     * otherwise. Names are display-only here — matching is always on the id, because a name is an
     * un-refreshed snapshot that a rename invalidates.
     */
    private static String describe(@Nullable String name, @Nullable UUID id) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return id != null ? id.toString() : "unknown";
    }

    private static String acceptedCodes(List<StorageCompatibility> rows) {
        return rows.stream()
                .map(StorageCompatibility::getStorageCategoryCode)
                .sorted()
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
    }

    /**
     * A compatibility decision.
     *
     * @param accepted whether the putaway may proceed
     * @param reason why not, when refused; null when accepted
     */
    public record Verdict(boolean accepted, @Nullable String reason) {

        /**
         * Named {@code accept}/{@code refuse} rather than {@code accepted}/{@code refused} because a
         * record's component accessor already owns the name {@code accepted()}.
         */
        public static @NonNull Verdict accept() {
            return new Verdict(true, null);
        }

        public static @NonNull Verdict refuse(@NonNull String reason) {
            return new Verdict(false, reason);
        }
    }
}
