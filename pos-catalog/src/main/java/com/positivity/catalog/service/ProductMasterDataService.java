package com.positivity.catalog.service;

import com.positivity.catalog.internal.dto.ProductCreateRequestDto;
import com.positivity.catalog.internal.dto.ProductDto;
import com.positivity.catalog.internal.dto.ProductSearchResultDto;
import com.positivity.catalog.internal.dto.ProductUpdateRequestDto;
import com.positivity.catalog.internal.entity.ProductStatus;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Pageable;

public interface ProductMasterDataService {

    ProductDto createProduct(@NonNull ProductCreateRequestDto request);

    ProductDto updateProduct(@NonNull UUID productId, @NonNull ProductUpdateRequestDto request);

    ProductDto changeProductStatus(@NonNull UUID productId, @NonNull ProductStatus newStatus);

    ProductSearchResultDto searchProducts(String q, String sku, String mpn, @NonNull Pageable pageable);
}
