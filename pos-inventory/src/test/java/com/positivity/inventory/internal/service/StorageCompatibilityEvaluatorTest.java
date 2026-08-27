package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.entity.StorageCompatibility;
import com.positivity.inventory.internal.enums.StorageCompatibilityMatchLevel;
import com.positivity.inventory.internal.repository.StorageCompatibilityRepository;
import com.positivity.inventory.internal.service.SkuCategoryLookup.SkuCategoryRef;
import com.positivity.inventory.internal.service.StorageLocationValidationService.StorageLocationValidation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Physical fitness, not paperwork: a tire belongs on a tire rack and a battery needs containment
 * (issue #1514). Replaces the two replenishment-policy gates, which meant a brand-new SKU could
 * never be put away anywhere while a tire could go into oil storage.
 *
 * <p>Catalog ids are the real seeded ones from {@code R__seed_reference_catalog.sql} so this test and
 * the {@code V43__storage_compatibility.sql} matrix cannot drift apart silently.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StorageCompatibilityEvaluator")
class StorageCompatibilityEvaluatorTest {

    private static final UUID TIRES_CATEGORY = UUID.fromString("01960030-0000-7000-8000-000000000001");
    private static final UUID ELECTRICAL_CATEGORY = UUID.fromString("01960030-0000-7000-8000-000000000004");
    private static final UUID FLUIDS_CATEGORY = UUID.fromString("01960030-0000-7000-8000-000000000007");
    private static final UUID BATTERIES_SUBCATEGORY = UUID.fromString("01960031-0000-7000-8000-00000000000e");
    private static final UUID PASSENGER_TIRES_SUBCATEGORY = UUID.fromString("01960031-0000-7000-8000-000000000003");

    private static final UUID DESTINATION = UUID.fromString("01960004-0001-7000-8000-000000000047");
    private static final String TIRE_SKU = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f0002";
    private static final String BATTERY_SKU = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f0001";
    private static final String OIL_SKU = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f0004";
    private static final String NEW_SKU = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f0009";

    @Mock
    private StorageCompatibilityRepository storageCompatibilityRepository;

    @Mock
    private SkuCategoryLookup skuCategoryLookup;

    private StorageCompatibilityEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new StorageCompatibilityEvaluator(storageCompatibilityRepository, skuCategoryLookup);
        // No subcategory overrides unless a test seeds one — the V43 default for most classes.
        when(storageCompatibilityRepository.findByMatchLevelAndCatalogRefId(
                        org.mockito.ArgumentMatchers.eq(StorageCompatibilityMatchLevel.SUBCATEGORY),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
    }

    private static StorageLocationValidation destination(String categoryCode, Boolean hazardContainment) {
        StorageLocationValidation validation = new StorageLocationValidation();
        validation.setStorageLocationId(DESTINATION);
        validation.setExists(true);
        validation.setActive(true);
        validation.setStorageCategoryCode(categoryCode);
        validation.setHazardContainment(hazardContainment);
        return validation;
    }

    private static StorageCompatibility row(
            StorageCompatibilityMatchLevel level, UUID refId, String code, boolean requiresContainment) {
        return StorageCompatibility.builder()
                .compatibilityId(UUID.randomUUID())
                .matchLevel(level)
                .catalogRefId(refId)
                .storageCategoryCode(code)
                .requiresContainment(requiresContainment)
                .build();
    }

    private void givenCategoryRows(UUID categoryId, StorageCompatibility... rows) {
        when(storageCompatibilityRepository.findByMatchLevelAndCatalogRefId(
                        StorageCompatibilityMatchLevel.CATEGORY, categoryId))
                .thenReturn(List.of(rows));
    }

    private void givenSubcategoryRows(UUID subcategoryId, StorageCompatibility... rows) {
        when(storageCompatibilityRepository.findByMatchLevelAndCatalogRefId(
                        StorageCompatibilityMatchLevel.SUBCATEGORY, subcategoryId))
                .thenReturn(List.of(rows));
    }

    private void givenSku(String skuId, SkuCategoryRef ref) {
        when(skuCategoryLookup.categoryRefOf(skuId)).thenReturn(Optional.ofNullable(ref));
    }

    private void givenTire() {
        givenSku(
                TIRE_SKU,
                new SkuCategoryRef(
                        TIRES_CATEGORY, "Tires & Wheels", PASSENGER_TIRES_SUBCATEGORY, "Passenger Car Tires"));
        givenCategoryRows(
                TIRES_CATEGORY,
                row(StorageCompatibilityMatchLevel.CATEGORY, TIRES_CATEGORY, "TIRE_RACK", false),
                row(StorageCompatibilityMatchLevel.CATEGORY, TIRES_CATEGORY, "BULK_FLOOR", false));
    }

    private void givenBattery() {
        givenSku(
                BATTERY_SKU,
                new SkuCategoryRef(ELECTRICAL_CATEGORY, "Electrical System", BATTERIES_SUBCATEGORY, "Batteries"));
        givenCategoryRows(
                ELECTRICAL_CATEGORY,
                row(StorageCompatibilityMatchLevel.CATEGORY, ELECTRICAL_CATEGORY, "SMALL_PARTS_BIN", false),
                row(StorageCompatibilityMatchLevel.CATEGORY, ELECTRICAL_CATEGORY, "GENERAL", false));
        givenSubcategoryRows(
                BATTERIES_SUBCATEGORY,
                row(StorageCompatibilityMatchLevel.SUBCATEGORY, BATTERIES_SUBCATEGORY, "BATTERY_RACK", true));
    }

    @Nested
    @DisplayName("class matching")
    class ClassMatching {

        @Test
        @DisplayName("#1514 - a tire is accepted by a tire rack")
        void tireAcceptedByTireRack() {
            givenTire();

            assertThat(evaluator
                            .evaluate(destination("TIRE_RACK", false), TIRE_SKU)
                            .accepted())
                    .isTrue();
        }

        @Test
        @DisplayName("#1514 - a tire is refused by oil storage, and the reason names both classes")
        void tireRefusedByOilStorage() {
            givenTire();

            StorageCompatibilityEvaluator.Verdict verdict =
                    evaluator.evaluate(destination("OIL_STORAGE", true), TIRE_SKU);

            assertThat(verdict.accepted()).isFalse();
            assertThat(verdict.reason())
                    .contains("OIL_STORAGE")
                    .contains("Tires & Wheels")
                    .contains("TIRE_RACK");
        }

        @Test
        @DisplayName("#1514 - a tire is refused by a small parts bin")
        void tireRefusedBySmallPartsBin() {
            givenTire();

            assertThat(evaluator
                            .evaluate(destination("SMALL_PARTS_BIN", false), TIRE_SKU)
                            .accepted())
                    .isFalse();
        }

        @Test
        @DisplayName("a lower-cased replica code still matches the matrix")
        void destinationCodeIsCaseInsensitive() {
            givenTire();

            assertThat(evaluator
                            .evaluate(destination("tire_rack", false), TIRE_SKU)
                            .accepted())
                    .isTrue();
        }

        @Test
        @DisplayName("a catalog class the matrix says nothing about is refused by a specific class")
        void unknownCatalogClassIsRefused() {
            givenSku(OIL_SKU, new SkuCategoryRef(FLUIDS_CATEGORY, "Fluids & Chemicals", null, null));
            givenCategoryRows(FLUIDS_CATEGORY);

            StorageCompatibilityEvaluator.Verdict verdict =
                    evaluator.evaluate(destination("BULK_FLOOR", false), OIL_SKU);

            assertThat(verdict.accepted()).isFalse();
            assertThat(verdict.reason()).contains("no storage compatibility is defined");
        }
    }

    @Nested
    @DisplayName("subcategory overrides")
    class SubcategoryOverrides {

        @Test
        @DisplayName("#1514 - a battery is accepted by a battery rack that declares containment")
        void batteryAcceptedByContainedBatteryRack() {
            givenBattery();

            assertThat(evaluator
                            .evaluate(destination("BATTERY_RACK", true), BATTERY_SKU)
                            .accepted())
                    .isTrue();
        }

        @Test
        @DisplayName("#1514 - a battery is refused by a battery rack with no containment declared")
        void batteryRefusedWithoutContainment() {
            givenBattery();

            StorageCompatibilityEvaluator.Verdict verdict =
                    evaluator.evaluate(destination("BATTERY_RACK", false), BATTERY_SKU);

            assertThat(verdict.accepted()).isFalse();
            assertThat(verdict.reason()).contains("hazard containment").contains("Batteries");
        }

        @Test
        @DisplayName("#1514 - null containment is not a declared false: it refuses too")
        void batteryRefusedWhenContainmentIsUnknown() {
            givenBattery();

            assertThat(evaluator
                            .evaluate(destination("BATTERY_RACK", null), BATTERY_SKU)
                            .accepted())
                    .isFalse();
        }

        @Test
        @DisplayName("#1514 - a battery does NOT inherit Electrical System's small-parts-bin permission")
        void batteryDoesNotInheritParentCategoryPermission() {
            givenBattery();

            // A contained small-parts bin isolates the class check from the containment check: this
            // destination satisfies containment and is still refused, which is the override doing
            // its job rather than the hazard gate doing it by accident.
            StorageCompatibilityEvaluator.Verdict verdict =
                    evaluator.evaluate(destination("SMALL_PARTS_BIN", true), BATTERY_SKU);

            assertThat(verdict.accepted()).isFalse();
            assertThat(verdict.reason()).contains("SMALL_PARTS_BIN").contains("BATTERY_RACK");
        }

        @Test
        @DisplayName("#1514 - an uncontained small parts bin refuses a battery on the containment gate")
        void batteryRefusedByAnUncontainedSmallPartsBin() {
            givenBattery();

            StorageCompatibilityEvaluator.Verdict verdict =
                    evaluator.evaluate(destination("SMALL_PARTS_BIN", false), BATTERY_SKU);

            assertThat(verdict.accepted()).isFalse();
            assertThat(verdict.reason()).contains("may only be stored with hazard containment");
        }

        @Test
        @DisplayName("with no override row the parent category's rows govern")
        void categoryRowsGovernWhenThereIsNoOverride() {
            givenSku(
                    BATTERY_SKU,
                    new SkuCategoryRef(ELECTRICAL_CATEGORY, "Electrical System", BATTERIES_SUBCATEGORY, "Batteries"));
            givenCategoryRows(
                    ELECTRICAL_CATEGORY,
                    row(StorageCompatibilityMatchLevel.CATEGORY, ELECTRICAL_CATEGORY, "SMALL_PARTS_BIN", false));

            assertThat(evaluator
                            .evaluate(destination("SMALL_PARTS_BIN", false), BATTERY_SKU)
                            .accepted())
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("containment on a category row")
    class CategoryLevelContainment {

        @Test
        @DisplayName("#1514 - oil is refused by oil storage with no containment declared")
        void oilRefusedByUncontainedOilStorage() {
            givenSku(OIL_SKU, new SkuCategoryRef(FLUIDS_CATEGORY, "Fluids & Chemicals", null, null));
            givenCategoryRows(
                    FLUIDS_CATEGORY,
                    row(StorageCompatibilityMatchLevel.CATEGORY, FLUIDS_CATEGORY, "OIL_STORAGE", true),
                    row(StorageCompatibilityMatchLevel.CATEGORY, FLUIDS_CATEGORY, "BULK_FLOOR", false));

            assertThat(evaluator
                            .evaluate(destination("OIL_STORAGE", false), OIL_SKU)
                            .accepted())
                    .isFalse();
            // The same item's non-containment-bearing option is unaffected.
            assertThat(evaluator
                            .evaluate(destination("BULK_FLOOR", false), OIL_SKU)
                            .accepted())
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("source-only classes")
    class SourceOnlyClasses {

        @Test
        @DisplayName("#1514 - STAGING is refused as a destination")
        void stagingRefusedAsDestination() {
            givenTire();

            StorageCompatibilityEvaluator.Verdict verdict = evaluator.evaluate(destination("STAGING", true), TIRE_SKU);

            assertThat(verdict.accepted()).isFalse();
            assertThat(verdict.reason()).contains("putaway sources, not destinations");
        }

        @Test
        @DisplayName("#1514 - QUARANTINE is refused as a destination")
        void quarantineRefusedAsDestination() {
            givenTire();

            assertThat(evaluator
                            .evaluate(destination("QUARANTINE", true), TIRE_SKU)
                            .accepted())
                    .isFalse();
        }

        @Test
        @DisplayName("a source-only class is refused even for an item the matrix knows nothing about")
        void sourceOnlyClassRefusesAnUnclassifiedItemToo() {
            givenSku(NEW_SKU, null);

            assertThat(evaluator.evaluate(destination("STAGING", true), NEW_SKU).accepted())
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("GENERAL and the uncategorised SKU")
    class GeneralAndUncategorised {

        @Test
        @DisplayName("#1514 - GENERAL accepts every category")
        void generalAcceptsEverything() {
            givenTire();

            assertThat(evaluator
                            .evaluate(destination("GENERAL", false), TIRE_SKU)
                            .accepted())
                    .isTrue();
        }

        @Test
        @DisplayName("#1514 - a brand-new SKU with no catalog class is accepted by GENERAL")
        void uncategorisedSkuAcceptedByGeneral() {
            givenSku(NEW_SKU, null);

            assertThat(evaluator
                            .evaluate(destination("GENERAL", false), NEW_SKU)
                            .accepted())
                    .isTrue();
        }

        @Test
        @DisplayName("#1514 - a brand-new SKU is refused by a specific class, and told why")
        void uncategorisedSkuRefusedByASpecificClass() {
            givenSku(NEW_SKU, null);

            StorageCompatibilityEvaluator.Verdict verdict =
                    evaluator.evaluate(destination("TIRE_RACK", false), NEW_SKU);

            assertThat(verdict.accepted()).isFalse();
            assertThat(verdict.reason()).contains("no catalog classification").contains("GENERAL");
        }

        @Test
        @DisplayName("an all-null classification is treated as no classification, not as a match")
        void emptyCategoryRefIsTreatedAsUnclassified() {
            givenSku(NEW_SKU, new SkuCategoryRef(null, null, null, null));

            assertThat(evaluator
                            .evaluate(destination("TIRE_RACK", false), NEW_SKU)
                            .accepted())
                    .isFalse();
        }

        @Test
        @DisplayName("a null replica capability resolves to GENERAL, keeping a cold replica usable")
        void nullReplicaCapabilityResolvesToGeneral() {
            givenTire();

            assertThat(evaluator.evaluate(destination(null, null), TIRE_SKU).accepted())
                    .isTrue();
        }

        @Test
        @DisplayName("a blank replica capability resolves to GENERAL too")
        void blankReplicaCapabilityResolvesToGeneral() {
            givenTire();

            assertThat(evaluator.evaluate(destination("   ", null), TIRE_SKU).accepted())
                    .isTrue();
        }
    }
}
