package com.positivity.supplier.internal.stockreport.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One article's reported availability within a stock snapshot (CAP-322; issue #1638 decision 5).
 *
 * <p>{@code availableQuantity} is nullable and the nullability is the contract: null means the
 * vendor reported the article without stating a quantity, zero means it explicitly reported none.
 * Collapsing the two would convert "the vendor said nothing" into "the vendor said none", which is
 * the one mistake this feed most needs to avoid.
 *
 * @param lineId immutable line identity (UUIDv7) within the snapshot
 * @param vendorLineId the vendor's own line id within the document, when stated
 * @param articleEan article EAN, when stated
 * @param supplierArticleCode the vendor's own article code — an alias for display, never an
 *     identifier
 * @param buyersArticleId the buyer's own code as the vendor holds it, when stated
 * @param description vendor-supplied article description, when stated
 * @param availableQuantity reported quantity; null when the vendor stated none, zero when it
 *     explicitly reported none
 */
@Schema(description = "One article's reported availability within a stock snapshot.")
public record StockSnapshotLineView(
        @Schema(
                description = "Immutable line identity within the snapshot.",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d")
        @NonNull
        UUID lineId,

        @Schema(description = "The vendor's own line id within the document, when stated.", example = "417") @Nullable
        String vendorLineId,

        @Schema(description = "Article EAN, when stated.", example = "3528709999083") @Nullable
        String articleEan,

        @Schema(
                description = "The vendor's own article code; an alias for display, never an identifier.",
                example = "999908")
        @Nullable
        String supplierArticleCode,

        @Schema(description = "The buyer's own code as the vendor holds it, when stated.", example = "TY-4471")
        @Nullable
        String buyersArticleId,

        @Schema(
                description = "Vendor-supplied article description, when stated.",
                example = "MICHELIN PILOT SPORT 5 225/45R17")
        @Nullable
        String description,

        @Schema(
                description = "Reported quantity. Null means the vendor stated NO quantity; zero means it explicitly"
                        + " reported none — the two are different answers and are never collapsed.")
        @Nullable
        Integer availableQuantity) {

    public StockSnapshotLineView {
        Objects.requireNonNull(lineId, "lineId must not be null");
    }
}
