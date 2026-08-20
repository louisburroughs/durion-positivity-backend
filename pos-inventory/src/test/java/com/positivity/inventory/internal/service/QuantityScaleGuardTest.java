package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.exception.FractionalQuantityNotAllowedException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The per-product divisibility rule as the supply side and the read side each apply it
 * (ADR-0055, #1414).
 *
 * <p>The two entry points deliberately answer differently when the product declares nothing.
 * {@code requirePostable} decides what may be written and defaults to whole units, which is the
 * ADR's "undeclared means integral". {@code requireReportable} describes what is already written,
 * where an absent declaration is not evidence that the stored value is wrong.
 */
class QuantityScaleGuardTest {

    private static final UUID WHOLE_UNITS_PRODUCT = UUID.fromString("018f0000-0000-7000-8000-000000000e01");
    private static final UUID SCALE_TWO_PRODUCT = UUID.fromString("018f0000-0000-7000-8000-000000000e02");

    private final UomConversionService uomConversionService = mock(UomConversionService.class);
    private final QuantityScaleGuard guard = new QuantityScaleGuard(uomConversionService);

    @Nested
    @DisplayName("requirePostable — the supply-side gate")
    class RequirePostable {

        @Test
        @DisplayName("refuses a fraction for a product that declares nothing")
        void refusesFractionForUndeclaredProduct() {
            when(uomConversionService.declaredBaseScale(WHOLE_UNITS_PRODUCT)).thenReturn(0);

            assertThatExceptionOfType(FractionalQuantityNotAllowedException.class)
                    .isThrownBy(() ->
                            guard.requirePostable(WHOLE_UNITS_PRODUCT, "PROD-1", "quantity", new BigDecimal("1.5")))
                    .satisfies(ex -> assertThat(ex.getMessage())
                            .contains(FractionalQuantityNotAllowedException.ERROR_CODE)
                            .contains("whole units"));
        }

        @Test
        @DisplayName("accepts a fraction within the declared scale")
        void acceptsFractionWithinDeclaredScale() {
            when(uomConversionService.declaredBaseScale(SCALE_TWO_PRODUCT)).thenReturn(2);

            assertThat(guard.requirePostable(SCALE_TWO_PRODUCT, "BULK-LB", "quantity", new BigDecimal("1.01")))
                    .isEqualByComparingTo("1.01");
        }

        @Test
        @DisplayName("refuses a fraction finer than the declared scale, and says how fine is allowed")
        void refusesFractionFinerThanDeclaredScale() {
            when(uomConversionService.declaredBaseScale(SCALE_TWO_PRODUCT)).thenReturn(2);

            assertThatExceptionOfType(FractionalQuantityNotAllowedException.class)
                    .isThrownBy(() ->
                            guard.requirePostable(SCALE_TWO_PRODUCT, "BULK-LB", "quantity", new BigDecimal("1.005")))
                    .satisfies(ex -> assertThat(ex.getMessage()).contains("2 decimal places"));
        }

        @Test
        @DisplayName("trailing zeros are spelling, not divisibility: 1.00 is a whole unit")
        void trailingZerosAreNotAFraction() {
            when(uomConversionService.declaredBaseScale(WHOLE_UNITS_PRODUCT)).thenReturn(0);

            // numeric(19,4) hands values back at scale 4, so without this every quantity read out
            // of the ledger would fail its own guard on the way back in.
            assertThat(guard.requirePostable(WHOLE_UNITS_PRODUCT, "PROD-1", "quantity", new BigDecimal("1.0000")))
                    .isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("a stock reference naming no catalog product is integral, like an undeclared one")
        void unresolvableReferenceIsIntegralOnTheWayIn() {
            when(uomConversionService.declaredBaseScale(null)).thenReturn(0);

            assertThatExceptionOfType(FractionalQuantityNotAllowedException.class)
                    .isThrownBy(() -> guard.requirePostable(null, "FREE-TEXT-SKU", "quantity", new BigDecimal("0.5")));
        }
    }

    @Nested
    @DisplayName("requireReportable — the fail-loud replacement for Math.toIntExact")
    class RequireReportable {

        @Test
        @DisplayName("stops the computation when the ledger holds precision no declaration supports")
        void refusesPrecisionNoDeclarationSupports() {
            when(uomConversionService.declaredBaseScale(WHOLE_UNITS_PRODUCT)).thenReturn(0);

            assertThatIllegalStateException()
                    .isThrownBy(() -> guard.requireReportable(
                            WHOLE_UNITS_PRODUCT, "PROD-1", "onHandQuantity", new BigDecimal("1.5")))
                    .withMessageContaining("precision_scale=0");
        }

        @Test
        @DisplayName("reports a divisible product's real quantity")
        void reportsDeclaredPrecision() {
            when(uomConversionService.declaredBaseScale(SCALE_TWO_PRODUCT)).thenReturn(2);

            assertThat(guard.requireReportable(
                            SCALE_TWO_PRODUCT, "BULK-LB", "onHandQuantity", new BigDecimal("120.5000")))
                    .isEqualByComparingTo("120.5");
        }

        @Test
        @DisplayName("reports what is stored when the stock reference names no catalog product")
        void unresolvableReferenceIsReportedNotRefused() {
            // The ledger's two posting paths disagree about whether stockItemId holds a product
            // UUID or a human SKU. A SKU-keyed row has no declaration to consult, and refusing to
            // report it would turn an availability read into a 500 and stall the replicas behind
            // it — for a value the ledger already accepted.
            assertThat(guard.requireReportable(null, "FREE-TEXT-SKU", "onHandQuantity", new BigDecimal("1.5")))
                    .isEqualByComparingTo("1.5");
        }
    }

    @Nested
    @DisplayName("productIdOf")
    class ProductIdOf {

        @Test
        @DisplayName("a UUID stock-item id names its catalog product")
        void uuidResolves() {
            assertThat(QuantityScaleGuard.productIdOf(SCALE_TWO_PRODUCT.toString()))
                    .isEqualTo(SCALE_TWO_PRODUCT);
        }

        @Test
        @DisplayName("a human SKU, a blank and a null name none")
        void nonUuidResolvesToNull() {
            assertThat(QuantityScaleGuard.productIdOf("PARK-387TC-4-FT")).isNull();
            assertThat(QuantityScaleGuard.productIdOf("  ")).isNull();
            assertThat(QuantityScaleGuard.productIdOf(null)).isNull();
        }
    }
}
