package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.exception.FractionalQuantityNotAllowedException;
import com.positivity.workorder.internal.repository.ExtProductUomReplicaRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The per-product divisibility rule (ADR-0055, #1413): a part quantity is integral unless the
 * product's {@code BASE} unit-of-measure row declares a decimal scale.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Part quantity divisibility — per-product scale from the catalog declaration")
class PartQuantityDivisibilityServiceTest {

    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fdd01");
    private static final String PART_LABEL = "Valvoline MaxLife ATF 1qt";

    @Mock
    private ExtProductUomReplicaRepository productUomRepository;

    @InjectMocks
    private PartQuantityDivisibilityService service;

    @Nested
    @DisplayName("Undeclared products are whole-unit")
    class Undeclared {

        @Test
        @DisplayName("resolves scale 0 when the product has no unit-of-measure rows")
        void undeclaredProductResolvesToZero() {
            // This is every product today: nothing seeds product_uom platform-wide, so the empty
            // answer is the common path and the one that preserves current behaviour exactly.
            when(productUomRepository.findBasePrecisionScales(PRODUCT_ID)).thenReturn(List.of());

            assertThat(service.declaredScaleFor(PRODUCT_ID)).isZero();
        }

        @Test
        @DisplayName("rejects a fractional quantity, naming the part, the value and the remedy")
        void rejectsFractionalQuantity() {
            when(productUomRepository.findBasePrecisionScales(PRODUCT_ID)).thenReturn(List.of());

            assertThatThrownBy(() -> service.requirePermittedScale(PRODUCT_ID, PART_LABEL, new BigDecimal("0.5")))
                    .isInstanceOf(FractionalQuantityNotAllowedException.class)
                    .hasMessage("Quantity 0.5 is not valid for 'Valvoline MaxLife ATF 1qt' — this part is issued"
                            + " and billed in whole units. Enter 1 to issue and bill the full container.");
        }

        @Test
        @DisplayName("carries the remedy as the nextAction the caller can act on")
        void carriesNextAction() {
            when(productUomRepository.findBasePrecisionScales(PRODUCT_ID)).thenReturn(List.of());

            assertThatThrownBy(() -> service.requirePermittedScale(PRODUCT_ID, PART_LABEL, new BigDecimal("2.25")))
                    .isInstanceOf(FractionalQuantityNotAllowedException.class)
                    .extracting(e -> ((FractionalQuantityNotAllowedException) e).getNextAction())
                    // Rounds up, not to nearest: an opened container is spent either way.
                    .isEqualTo("Enter 3 to issue and bill the full container.");
        }

        @Test
        @DisplayName("accepts a whole quantity, trailing zeros included")
        void acceptsWholeQuantity() {
            when(productUomRepository.findBasePrecisionScales(PRODUCT_ID)).thenReturn(List.of());

            // 1.00 is how a client serialised the number, not a claim that the product is divisible.
            assertThatCode(() -> service.requirePermittedScale(PRODUCT_ID, PART_LABEL, new BigDecimal("1.00")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("treats an explicit scale-0 declaration the same as no declaration")
        void explicitZeroScaleBehavesAsUndeclared() {
            when(productUomRepository.findBasePrecisionScales(PRODUCT_ID)).thenReturn(List.of(0));

            assertThatThrownBy(() -> service.requirePermittedScale(PRODUCT_ID, PART_LABEL, new BigDecimal("0.5")))
                    .isInstanceOf(FractionalQuantityNotAllowedException.class)
                    .hasMessageContaining("whole units");
        }
    }

    @Nested
    @DisplayName("Products declaring a decimal scale")
    class Declared {

        @Test
        @DisplayName("accepts a quantity within the declared scale")
        void acceptsWithinDeclaredScale() {
            when(productUomRepository.findBasePrecisionScales(PRODUCT_ID)).thenReturn(List.of(2));

            assertThatCode(() ->
                            service.requirePermittedScale(PRODUCT_ID, "Parker Hydraulic Hose", new BigDecimal("1.01")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects a quantity finer than the declared scale, naming the scale")
        void rejectsBeyondDeclaredScale() {
            when(productUomRepository.findBasePrecisionScales(PRODUCT_ID)).thenReturn(List.of(2));

            assertThatThrownBy(() ->
                            service.requirePermittedScale(PRODUCT_ID, "Parker Hydraulic Hose", new BigDecimal("1.001")))
                    .isInstanceOf(FractionalQuantityNotAllowedException.class)
                    .hasMessage("Quantity 1.001 is not valid for 'Parker Hydraulic Hose' — this part is stocked"
                            + " to 2 decimal places. Enter a quantity with at most 2 decimal places, such as 1.01.");
        }

        @Test
        @DisplayName("takes the widest scale when a product mis-declares two BASE rows")
        void takesWidestScaleOnDuplicateBaseRows() {
            // A seeding defect, not a domain case. Blocking every part entry for the product would
            // punish the shop for a catalog error.
            when(productUomRepository.findBasePrecisionScales(PRODUCT_ID)).thenReturn(List.of(0, 3));

            assertThat(service.declaredScaleFor(PRODUCT_ID)).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Non-catalog lines")
    class NonCatalog {

        @Test
        @DisplayName("exempts a part with no productEntityId and never looks a product up")
        void exemptsNonCatalogPart() {
            // Labour, shop supplies, non-stocked consumables: no declaration exists to enforce.
            assertThatCode(() -> service.requirePermittedScale(null, "Shop supplies", new BigDecimal("0.25")))
                    .doesNotThrowAnyException();

            verify(productUomRepository, never()).findBasePrecisionScales(any());
        }
    }
}
