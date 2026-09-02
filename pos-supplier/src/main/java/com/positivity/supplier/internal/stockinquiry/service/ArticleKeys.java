package com.positivity.supplier.internal.stockinquiry.service;

import org.jspecify.annotations.Nullable;

/**
 * The article identity a stock answer is remembered and matched by, shared by the per-vendor
 * inquiry path and the product-keyed availability fan-out so a fresh answer from either serves a
 * cache hit for the other.
 *
 * <p>Both identifiers participate: a vendor may echo either, and keying on one alone would let an
 * answer about an article identified by EAN be served for a request that named a supplier code,
 * which are not guaranteed to be the same article.
 */
final class ArticleKeys {

    private ArticleKeys() {}

    static String of(@Nullable String articleEan, @Nullable String supplierArticleCode) {
        return normalise(articleEan) + "|" + normalise(supplierArticleCode);
    }

    private static String normalise(@Nullable String value) {
        return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
