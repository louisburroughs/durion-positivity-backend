package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.ExtProductReplica;
import com.positivity.inventory.internal.entity.ExtProductUomReplica;
import com.positivity.inventory.internal.exception.UomConversionUndefinedException;
import com.positivity.inventory.internal.repository.ExtProductReplicaRepository;
import com.positivity.inventory.internal.repository.ExtProductUomReplicaRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replica-backed {@link UomConversionService} (odoo-parity B1/B3, #1033).
 *
 * <p>Conversion resolves the product's {@code ext_product} row, multiplies by the
 * {@code ext_product_uom} factor (identity for the base UoM itself), and rounds at the base UoM's
 * precision scale — {@code HALF_UP} generally, {@code DOWN} for reservations. The base scale
 * comes from the base UoM's own conversion row (catalog emits a {@code BASE} row per product);
 * when the replica carries no base row the raw product is returned unrounded rather than
 * inventing a scale.
 */
@Service
@RequiredArgsConstructor
public class UomConversionServiceImpl implements UomConversionService {

    /** What a product that declares nothing resolves to: whole units (ADR-0055, #1414). */
    private static final int UNDECLARED_SCALE = 0;

    private final ExtProductReplicaRepository extProductReplicaRepository;
    private final ExtProductUomReplicaRepository extProductUomReplicaRepository;

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public BigDecimal toBaseQuantity(@NonNull UUID productId, @NonNull String uomCode, @NonNull BigDecimal quantity) {
        return convert(productId, uomCode, quantity, RoundingMode.HALF_UP);
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public BigDecimal toBaseQuantityForReservation(
            @NonNull UUID productId, @NonNull String uomCode, @NonNull BigDecimal quantity) {
        return convert(productId, uomCode, quantity, RoundingMode.DOWN);
    }

    @Override
    @Transactional(readOnly = true)
    public int precisionScale(@NonNull UUID productId, @NonNull String uomCode) {
        if (!extProductReplicaRepository.existsById(productId)) {
            throw UomConversionUndefinedException.unknownProduct(productId, uomCode);
        }
        return extProductUomReplicaRepository
                .findByProductIdAndUomCode(productId, uomCode)
                .map(ExtProductUomReplica::getPrecisionScale)
                .orElseThrow(() -> UomConversionUndefinedException.unknownUom(productId, uomCode));
    }

    @Override
    @Transactional(readOnly = true)
    public int declaredBaseScale(@Nullable UUID productId) {
        if (productId == null) {
            return UNDECLARED_SCALE;
        }
        String baseUom = extProductReplicaRepository
                .findById(productId)
                .map(ExtProductReplica::getBaseUom)
                .orElse(null);
        if (baseUom == null || baseUom.isBlank()) {
            return UNDECLARED_SCALE;
        }
        try {
            return precisionScale(productId, baseUom);
        } catch (UomConversionUndefinedException _) {
            // The product exists but declares no BASE conversion row, which is every product
            // today. Undeclared means whole units — see the interface contract.
            return UNDECLARED_SCALE;
        }
    }

    private BigDecimal convert(UUID productId, String uomCode, BigDecimal quantity, RoundingMode roundingMode) {
        ExtProductReplica product = extProductReplicaRepository
                .findById(productId)
                .orElseThrow(() -> UomConversionUndefinedException.unknownProduct(productId, uomCode));
        boolean isBase = uomCode.equals(product.getBaseUom());
        BigDecimal raw;
        if (isBase) {
            // Identity path: the base UoM converts to itself with factor 1.
            raw = quantity;
        } else {
            ExtProductUomReplica row = extProductUomReplicaRepository
                    .findByProductIdAndUomCode(productId, uomCode)
                    .orElseThrow(() -> UomConversionUndefinedException.unknownUom(productId, uomCode));
            raw = quantity.multiply(row.getFactorToBase());
        }
        Integer baseScale = baseScale(product);
        return baseScale == null ? raw : raw.setScale(baseScale, roundingMode);
    }

    /** Base UoM precision scale from the base UoM's own conversion row; null when absent. */
    private Integer baseScale(ExtProductReplica product) {
        if (product.getBaseUom() == null) {
            return null;
        }
        return extProductUomReplicaRepository
                .findByProductIdAndUomCode(product.getProductId(), product.getBaseUom())
                .map(ExtProductUomReplica::getPrecisionScale)
                .orElse(null);
    }
}
