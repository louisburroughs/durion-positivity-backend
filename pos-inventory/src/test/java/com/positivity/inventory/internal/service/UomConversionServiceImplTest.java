package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.entity.ExtProductReplica;
import com.positivity.inventory.internal.entity.ExtProductUomReplica;
import com.positivity.inventory.internal.exception.UomConversionUndefinedException;
import com.positivity.inventory.internal.repository.ExtProductReplicaRepository;
import com.positivity.inventory.internal.repository.ExtProductUomReplicaRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Rounding-policy vectors for the UoM conversion service (odoo-parity B1/B3, #1033): HALF_UP at
 * the base UoM's precision scale generally, DOWN for reservations (never promise more than
 * exists), identity path for the base UoM itself, and deterministic
 * {@code UOM_CONVERSION_UNDEFINED} for unknown products/UoMs — never a silent 1:1 assumption.
 */
class UomConversionServiceImplTest {

    private static final UUID PRODUCT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000d01");
    private static final UUID FRACTIONAL_PRODUCT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000d02");
    private static final UUID UNKNOWN_PRODUCT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000d99");

    private final ExtProductReplicaRepository extProduct = mock(ExtProductReplicaRepository.class);
    private final ExtProductUomReplicaRepository extProductUom = mock(ExtProductUomReplicaRepository.class);

    private UomConversionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UomConversionServiceImpl(extProduct, extProductUom);

        // Product 1: base EA (scale 0), CASE = 12 EA (scale 2).
        stubProduct(PRODUCT_ID, "EA");
        stubUom(PRODUCT_ID, "EA", "BASE", "1", 0);
        stubUom(PRODUCT_ID, "CASE", "PURCHASE", "12", 2);

        // Product 2: base LB (scale 2), BAG = 1.005 LB — exercises HALF_UP vs DOWN at scale 2.
        stubProduct(FRACTIONAL_PRODUCT_ID, "LB");
        stubUom(FRACTIONAL_PRODUCT_ID, "LB", "BASE", "1", 2);
        stubUom(FRACTIONAL_PRODUCT_ID, "BAG", "PACK", "1.005", 3);

        when(extProduct.findById(UNKNOWN_PRODUCT_ID)).thenReturn(Optional.empty());
        when(extProduct.existsById(UNKNOWN_PRODUCT_ID)).thenReturn(false);
    }

    private void stubProduct(UUID productId, String baseUom) {
        ExtProductReplica replica = ExtProductReplica.builder()
                .productId(productId)
                .baseUom(baseUom)
                .trackingLevel("NONE")
                .aggregateVersion(1L)
                .updatedAt(Instant.parse("2026-07-20T00:00:00Z"))
                .build();
        when(extProduct.findById(productId)).thenReturn(Optional.of(replica));
        when(extProduct.existsById(productId)).thenReturn(true);
    }

    private void stubUom(UUID productId, String uomCode, String uomType, String factor, int precisionScale) {
        when(extProductUom.findByProductIdAndUomCode(productId, uomCode))
                .thenReturn(Optional.of(ExtProductUomReplica.builder()
                        .productId(productId)
                        .uomCode(uomCode)
                        .uomType(uomType)
                        .factorToBase(new BigDecimal(factor))
                        .precisionScale(precisionScale)
                        .build()));
    }

    @Nested
    @DisplayName("toBaseQuantity — HALF_UP at the base UoM precision scale")
    class ToBaseQuantity {

        @Test
        @DisplayName("1 CASE(12 EA) → 12 EA — the spec acceptance vector")
        void wholeCaseConverts() {
            assertThat(service.toBaseQuantity(PRODUCT_ID, "CASE", BigDecimal.ONE))
                    .isEqualByComparingTo("12");
        }

        @Test
        @DisplayName("0.21 CASE = 2.52 EA → 3 EA at base scale 0 (HALF_UP)")
        void roundsHalfUpAtBaseScale() {
            assertThat(service.toBaseQuantity(PRODUCT_ID, "CASE", new BigDecimal("0.21")))
                    .isEqualByComparingTo("3");
        }

        @Test
        @DisplayName("0.04 CASE = 0.48 EA → 0 EA at base scale 0 (HALF_UP down)")
        void roundsHalfUpDownwardsBelowMidpoint() {
            assertThat(service.toBaseQuantity(PRODUCT_ID, "CASE", new BigDecimal("0.04")))
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("1 BAG = 1.005 LB → 1.01 LB at base scale 2 (HALF_UP at midpoint)")
        void roundsHalfUpAtFractionalBaseScale() {
            assertThat(service.toBaseQuantity(FRACTIONAL_PRODUCT_ID, "BAG", BigDecimal.ONE))
                    .isEqualByComparingTo("1.01");
        }

        @Test
        @DisplayName("Base UoM identity path: 5 EA → 5 EA, 2.5 EA → 3 EA at base scale 0")
        void baseUomIsIdentityAtBaseScale() {
            assertThat(service.toBaseQuantity(PRODUCT_ID, "EA", new BigDecimal("5")))
                    .isEqualByComparingTo("5");
            assertThat(service.toBaseQuantity(PRODUCT_ID, "EA", new BigDecimal("2.5")))
                    .isEqualByComparingTo("3");
        }

        @Test
        @DisplayName("Result carries exactly the base precision scale")
        void resultUsesBasePrecisionScale() {
            assertThat(service.toBaseQuantity(FRACTIONAL_PRODUCT_ID, "BAG", new BigDecimal("2"))
                            .scale())
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("toBaseQuantityForReservation — DOWN, never promise more than exists")
    class ToBaseQuantityForReservation {

        @Test
        @DisplayName("0.21 CASE = 2.52 EA → 2 EA (DOWN where HALF_UP gives 3)")
        void roundsDown() {
            assertThat(service.toBaseQuantityForReservation(PRODUCT_ID, "CASE", new BigDecimal("0.21")))
                    .isEqualByComparingTo("2");
        }

        @Test
        @DisplayName("1 BAG = 1.005 LB → 1.00 LB (DOWN at midpoint where HALF_UP gives 1.01)")
        void roundsDownAtMidpoint() {
            assertThat(service.toBaseQuantityForReservation(FRACTIONAL_PRODUCT_ID, "BAG", BigDecimal.ONE))
                    .isEqualByComparingTo("1.00");
        }

        @Test
        @DisplayName("Exact conversions are unchanged: 1 CASE → 12 EA")
        void exactConversionUnchanged() {
            assertThat(service.toBaseQuantityForReservation(PRODUCT_ID, "CASE", BigDecimal.ONE))
                    .isEqualByComparingTo("12");
        }

        @Test
        @DisplayName("Base UoM identity path rounds DOWN: 2.5 EA → 2 EA")
        void baseUomIdentityRoundsDown() {
            assertThat(service.toBaseQuantityForReservation(PRODUCT_ID, "EA", new BigDecimal("2.5")))
                    .isEqualByComparingTo("2");
        }
    }

    @Nested
    @DisplayName("precisionScale")
    class PrecisionScale {

        @Test
        @DisplayName("Returns the replica row's scale per UoM")
        void returnsRowScale() {
            assertThat(service.precisionScale(PRODUCT_ID, "EA")).isZero();
            assertThat(service.precisionScale(PRODUCT_ID, "CASE")).isEqualTo(2);
            assertThat(service.precisionScale(FRACTIONAL_PRODUCT_ID, "BAG")).isEqualTo(3);
        }

        @Test
        @DisplayName("Unknown UoM → UOM_CONVERSION_UNDEFINED")
        void unknownUomThrows() {
            assertThatExceptionOfType(UomConversionUndefinedException.class)
                    .isThrownBy(() -> service.precisionScale(PRODUCT_ID, "PALLET"))
                    .satisfies(ex -> assertThat(ex.getErrorCode()).isEqualTo("UOM_CONVERSION_UNDEFINED"));
        }

        @Test
        @DisplayName("Unknown product → UOM_CONVERSION_UNDEFINED")
        void unknownProductThrows() {
            assertThatExceptionOfType(UomConversionUndefinedException.class)
                    .isThrownBy(() -> service.precisionScale(UNKNOWN_PRODUCT_ID, "EA"));
        }
    }

    @Nested
    @DisplayName("Undefined conversions — deterministic 422, never a silent 1:1")
    class UndefinedConversions {

        @Test
        @DisplayName("Unknown UoM for a known product throws")
        void unknownUomThrows() {
            assertThatExceptionOfType(UomConversionUndefinedException.class)
                    .isThrownBy(() -> service.toBaseQuantity(PRODUCT_ID, "PALLET", BigDecimal.ONE))
                    .satisfies(ex -> {
                        assertThat(ex.getErrorCode()).isEqualTo("UOM_CONVERSION_UNDEFINED");
                        assertThat(ex.getProductId()).isEqualTo(PRODUCT_ID);
                        assertThat(ex.getUomCode()).isEqualTo("PALLET");
                    });
        }

        @Test
        @DisplayName("Unknown product throws on both conversion variants")
        void unknownProductThrows() {
            assertThatExceptionOfType(UomConversionUndefinedException.class)
                    .isThrownBy(() -> service.toBaseQuantity(UNKNOWN_PRODUCT_ID, "EA", BigDecimal.ONE));
            assertThatExceptionOfType(UomConversionUndefinedException.class)
                    .isThrownBy(() -> service.toBaseQuantityForReservation(UNKNOWN_PRODUCT_ID, "EA", BigDecimal.ONE));
        }

        @Test
        @DisplayName("Replica without a base-UoM row: conversion still works, result unrounded")
        void missingBaseRowLeavesResultUnrounded() {
            UUID productId = UUID.fromString("018f0000-0000-7000-8000-000000000d03");
            ExtProductReplica replica = ExtProductReplica.builder()
                    .productId(productId)
                    .baseUom("EA")
                    .trackingLevel("NONE")
                    .aggregateVersion(1L)
                    .updatedAt(Instant.parse("2026-07-20T00:00:00Z"))
                    .build();
            when(extProduct.findById(productId)).thenReturn(Optional.of(replica));
            when(extProductUom.findByProductIdAndUomCode(productId, "EA")).thenReturn(Optional.empty());
            when(extProductUom.findByProductIdAndUomCode(productId, "CASE"))
                    .thenReturn(Optional.of(ExtProductUomReplica.builder()
                            .productId(productId)
                            .uomCode("CASE")
                            .uomType("PURCHASE")
                            .factorToBase(new BigDecimal("12"))
                            .precisionScale(2)
                            .build()));

            assertThat(service.toBaseQuantity(productId, "CASE", new BigDecimal("0.21")))
                    .isEqualByComparingTo("2.52");
        }
    }
}
