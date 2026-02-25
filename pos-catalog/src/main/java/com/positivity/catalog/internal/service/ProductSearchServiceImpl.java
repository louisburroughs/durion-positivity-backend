package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.CatalogSearchResultDto;
import com.positivity.catalog.internal.dto.ProductSummary;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.repository.ProductRepository;
import com.positivity.catalog.service.ProductSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Implementation of {@link ProductSearchService} providing cursor-based
 * catalog product search with brand and category filtering.
 *
 * <p>
 * Cursor encoding: Base64(URL-safe, no padding) of the page number as a
 * decimal string. Page 0 = first page (no cursor required).
 *
 * <p>
 * Limit is clamped to [1, {@value MAX_LIMIT}] regardless of the caller's
 * requested value — consistent with contract assertion CS-008.
 *
 * Issue: CAP-247 Story #17
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSearchServiceImpl implements ProductSearchService {

    private static final int MAX_LIMIT = 100;

    private final ProductRepository productRepository;

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public CatalogSearchResultDto searchProducts(
            String q,
            String brand,
            String category,
            String sku,
            String cursor,
            int limit) {

        int effectiveLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        int pageNumber = decodeCursor(cursor);

        // Normalize blank strings to null so JPQL treats them as "no filter"
        String normalizedQ = (q == null || q.isBlank()) ? null : q.strip();
        String normalizedSku = (sku == null || sku.isBlank()) ? null : sku.strip();
        String normalizedBrand = (brand == null || brand.isBlank()) ? null : brand.strip();
        String normalizedCategory = (category == null || category.isBlank()) ? null : category.strip();

        log.debug("Catalog search: q={}, brand={}, category={}, sku={}, page={}, limit={}",
                normalizedQ, normalizedBrand, normalizedCategory, normalizedSku, pageNumber, effectiveLimit);

        PageRequest pageable = PageRequest.of(pageNumber, effectiveLimit, Sort.by("name").ascending());
        Page<ProductEntity> page = productRepository.searchProductsFiltered(
                normalizedQ, normalizedSku, normalizedBrand, normalizedCategory, pageable);

        List<ProductSummary> data = page.getContent().stream()
                .map(ProductSearchServiceImpl::toSummary)
                .collect(Collectors.toList());

        String nextCursor = page.hasNext() ? encodeCursor(pageNumber + 1) : null;

        return CatalogSearchResultDto.builder()
                .data(data)
                .nextCursor(nextCursor)
                .limit(effectiveLimit)
                .build();
    }

    // -----------------------------------------------------------------------
    // Cursor helpers
    // -----------------------------------------------------------------------

    /**
     * Encodes a page number as an opaque Base64 (URL-safe, no padding) cursor.
     */
    static String encodeCursor(int pageNumber) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.valueOf(pageNumber).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a cursor string back to a page number. Returns 0 for null, blank, or
     * malformed cursors (i.e., defaults to the first page).
     */
    static int decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            return Math.max(0, Integer.parseInt(decoded));
        } catch (Exception e) {
            log.warn("Invalid cursor '{}', defaulting to first page", cursor);
            return 0;
        }
    }

    /**
     * Maps a {@link ProductEntity} to a lightweight {@link ProductSummary} DTO.
     */
    static ProductSummary toSummary(ProductEntity entity) {
        String thumbnailUrl = entity.getImages() != null && !entity.getImages().isEmpty()
                ? entity.getImages().get(0)
                : null;
        String categoryName = entity.getCategory() != null ? entity.getCategory().getName() : null;

        return ProductSummary.builder()
                .productId(entity.getId())
                .name(entity.getName())
                .sku(entity.getSku())
                .category(categoryName)
                .thumbnailUrl(thumbnailUrl)
                .manufacturerBrand(entity.getManufacturerBrand())
                .build();
    }
}
