package com.positivity.domainevents.catalog;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Payload for {@code catalog.supplier_article_code.updated} on {@code catalog.events.v1}
 * (CAP-320 #1347, ADR-0044 §6).
 *
 * <p>The vendor's own article code for one product at one vendor. Distinct from
 * {@link ProductUpdatedV1#productCode}, which is the product's own EAN/UPC and is one value per
 * product: the same product legitimately carries a different code at every vendor that sells it,
 * so this fact is keyed per {@code (vendorProfileId, productId)} rather than per product.
 *
 * <p>Published by pos-catalog whenever a PRICAT import applies a line stating a vendor article
 * code (ADR-0053 §5) — the code is display/correspondence data there, never a match key, but
 * purchase-order transmission (pos-order, CAP-320 #1330) needs it to name a line to a vendor that
 * identifies articles by its own codes rather than by EAN, which it otherwise cannot do at all.
 *
 * <p>{@code supplierRef} travels alongside {@code vendorProfileId} because the purchase-order
 * aggregate in pos-order holds only the descriptive alias, never the vendor profile id — it is the
 * join key this fact's consumers actually have (ADR-0044 R1: pos-order may not read pos-catalog or
 * pos-supplier synchronously to resolve one from the other).
 *
 * @param vendorProfileId vendor profile identity from pos-supplier (ADR-0050 §1); canonical key
 * @param supplierRef     vendor profile alias as at the import; the key pos-order's purchase
 *                        orders actually carry
 * @param productId       catalog product the code belongs to (also the envelope aggregateId)
 * @param supplierArticleCode the vendor's own article code; never blank
 */
public record SupplierArticleCodeUpdatedV1(
        @NonNull UUID vendorProfileId,
        @NonNull String supplierRef,
        @NonNull UUID productId,
        @NonNull String supplierArticleCode) {

    /** Event type of this payload on {@code catalog.events.v1}. */
    public static final String EVENT_TYPE = "catalog.supplier_article_code.updated";

    /** Payload schema version; additive changes only within v1 (ADR-0044 §3). */
    public static final int SCHEMA_VERSION = 1;

    public SupplierArticleCodeUpdatedV1 {
        Objects.requireNonNull(vendorProfileId, "vendorProfileId must not be null");
        Objects.requireNonNull(supplierRef, "supplierRef must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(supplierArticleCode, "supplierArticleCode must not be null");
        if (supplierRef.isBlank()) {
            throw new IllegalArgumentException("supplierRef must not be blank");
        }
        if (supplierArticleCode.isBlank()) {
            throw new IllegalArgumentException("supplierArticleCode must not be blank");
        }
    }
}
