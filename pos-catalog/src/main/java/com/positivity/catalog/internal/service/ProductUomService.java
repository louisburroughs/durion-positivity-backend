package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.ProductUomCreateRequestDto;
import com.positivity.catalog.internal.dto.ProductUomDto;
import com.positivity.catalog.internal.dto.ProductUomUpdateRequestDto;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Manages a product's UoM conversion set (#1023, inventory Odoo-parity Story X1): purchase/pack
 * UoMs with conversion factor to the product's base UoM and a precision scale. Every mutation
 * re-emits the product's {@code catalog.product.updated} fact so downstream replicas stay in sync.
 */
public interface ProductUomService {

    ProductUomDto addProductUom(@NonNull UUID productId, @NonNull ProductUomCreateRequestDto request);

    List<ProductUomDto> listProductUoms(@NonNull UUID productId);

    ProductUomDto updateProductUom(
            @NonNull UUID productId, @NonNull UUID uomId, @NonNull ProductUomUpdateRequestDto request);

    void deleteProductUom(@NonNull UUID productId, @NonNull UUID uomId);
}
