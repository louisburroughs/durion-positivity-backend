package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.entity.ExtProduct;
import com.positivity.order.internal.entity.ExtProductUomReplica;
import com.positivity.order.internal.exception.PurchaseOrderRequestValidationException;
import com.positivity.order.internal.exception.UomConversionUndefinedException;
import com.positivity.order.internal.repository.ExtProductRepository;
import com.positivity.order.internal.repository.ExtProductUomReplicaRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Document-UoM conversion on purchase-order lines (CAP-320 #1334).
 *
 * <p>The failure this guards against is quiet: an order for "1 CASE" silently becoming an order
 * for 1 unit. Nothing downstream can tell the difference until the delivery arrives short, so
 * every path that cannot convert has to fail loudly instead of assuming 1:1.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DocumentQuantityConverter — purchase-order lines keyed in a document UoM")
class DocumentQuantityConverterTest {

    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a04");

    @Mock
    private ExtProductRepository extProductRepository;

    @Mock
    private ExtProductUomReplicaRepository extProductUomReplicaRepository;

    private DocumentQuantityConverter converter;

    @BeforeEach
    void setUp() {
        converter = new DocumentQuantityConverter(extProductRepository, extProductUomReplicaRepository);
        ExtProduct product = new ExtProduct();
        product.setProductId(PRODUCT_ID);
        product.setBaseUom("EA");
        when(extProductRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(extProductUomReplicaRepository.findByProductIdAndUomCode(any(), any()))
                .thenReturn(Optional.empty());
        uom("EA", "1", 0);
    }

    private void uom(String code, String factor, int scale) {
        when(extProductUomReplicaRepository.findByProductIdAndUomCode(PRODUCT_ID, code))
                .thenReturn(Optional.of(ExtProductUomReplica.builder()
                        .productId(PRODUCT_ID)
                        .uomCode(code)
                        .factorToBase(new BigDecimal(factor))
                        .precisionScale(scale)
                        .build()));
    }

    @Test
    @DisplayName("a line keyed in base UoM needs no conversion at all")
    void absentPairReturnsEmpty() {
        assertThat(converter.convertIfPresent(PRODUCT_ID, "sku", null, null)).isEmpty();
    }

    @Test
    @DisplayName("a case converts to its base quantity and records the factor actually applied")
    void convertsAndRecordsTheEffectiveFactor() {
        uom("CASE", "12", 0);

        DocumentQuantityConverter.DocumentConversion conversion = converter
                .convertIfPresent(PRODUCT_ID, "sku", "CASE", new BigDecimal("2"))
                .orElseThrow();

        assertThat(conversion.baseQuantity()).isEqualByComparingTo("24");
        // The factor is derived from the rounded result rather than copied from the replica, so
        // the audit trail says what was actually applied — including any rounding.
        assertThat(conversion.conversionFactor()).isEqualByComparingTo("12");
        assertThat(conversion.documentQuantity()).isEqualByComparingTo("2");
        assertThat(conversion.documentUom()).isEqualTo("CASE");
    }

    @Test
    @DisplayName("the product's own base UoM is an identity conversion, not a missing one")
    void baseUomIsIdentity() {
        DocumentQuantityConverter.DocumentConversion conversion = converter
                .convertIfPresent(PRODUCT_ID, "sku", "EA", new BigDecimal("5"))
                .orElseThrow();

        assertThat(conversion.baseQuantity()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("results round at the base UoM's precision scale")
    void roundsAtBaseScale() {
        ExtProduct product = new ExtProduct();
        product.setProductId(PRODUCT_ID);
        product.setBaseUom("L");
        when(extProductRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        uom("L", "1", 2);
        uom("DRUM", "1.005", 2);

        DocumentQuantityConverter.DocumentConversion conversion = converter
                .convertIfPresent(PRODUCT_ID, "sku", "DRUM", BigDecimal.ONE)
                .orElseThrow();

        assertThat(conversion.baseQuantity()).isEqualByComparingTo("1.01");
    }

    @Test
    @DisplayName("an unknown UoM is a hard failure, never a 1:1 assumption")
    void unknownUomThrows() {
        assertThatThrownBy(() -> converter.convertIfPresent(PRODUCT_ID, "sku", "PALLET", BigDecimal.ONE))
                .isInstanceOf(UomConversionUndefinedException.class)
                .hasMessageContaining("UOM_CONVERSION_UNDEFINED");
    }

    @Test
    @DisplayName("an unknown product is a hard failure too")
    void unknownProductThrows() {
        when(extProductRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> converter.convertIfPresent(PRODUCT_ID, "sku", "CASE", BigDecimal.ONE))
                .isInstanceOf(UomConversionUndefinedException.class);
    }

    @Test
    @DisplayName("a document UoM on a line with no resolvable product cannot be converted")
    void unresolvableProductThrows() {
        assertThatThrownBy(() -> converter.convertIfPresent(null, "free-text-sku", "CASE", BigDecimal.ONE))
                .isInstanceOf(UomConversionUndefinedException.class)
                .hasMessageContaining("free-text-sku");
    }

    @Test
    @DisplayName("half a pair is a caller error, not a conversion problem")
    void halfAPairIsRejected() {
        assertThatThrownBy(() -> converter.convertIfPresent(PRODUCT_ID, "sku", "CASE", null))
                .isInstanceOf(PurchaseOrderRequestValidationException.class);
        assertThatThrownBy(() -> converter.convertIfPresent(PRODUCT_ID, "sku", null, BigDecimal.ONE))
                .isInstanceOf(PurchaseOrderRequestValidationException.class);
    }

    @Test
    @DisplayName("a non-positive document quantity is rejected")
    void nonPositiveQuantityIsRejected() {
        assertThatThrownBy(() -> converter.convertIfPresent(PRODUCT_ID, "sku", "CASE", BigDecimal.ZERO))
                .isInstanceOf(PurchaseOrderRequestValidationException.class);
    }
}
