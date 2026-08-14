package com.positivity.supplier.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One quarantined PRICAT line awaiting a catalog fix (ADR-0053 §5).
 *
 * <p>This is the operator worklist that makes "zero silent drops" actionable: each row names what
 * the vendor sent and why it could not be attached to a product. The prices the vendor stated are
 * shown so the line can be judged without a second vendor call, and they are display data only —
 * an unmatched line has no product, so nothing downstream can price against it.
 *
 * @param unmatchedLineId     identity of the quarantine row
 * @param importManifestId    import that produced the line
 * @param vendorProfileId     vendor profile the catalog was fetched for
 * @param positionNumber      1-based line position in the vendor document, when stated
 * @param articleEan          EAN the line carried, when stated
 * @param supplierArticleCode vendor's own article code — an alias, never an identifier
 * @param xReferenceCode      cross-reference code the line carried, when stated
 * @param reason              why the line was quarantined
 * @param reasonDetail        operator-facing detail for the reason
 * @param netPrice            net price the vendor stated, when stated
 * @param grossPrice          gross price the vendor stated, when stated
 * @param effectiveFrom       inclusive first day the values would apply, when stated
 * @param currency            currency of the stated amounts, when known
 * @param fetchedAt           when the import fetched the line
 * @param resolvedAt          when a later match closed the line; null while quarantined
 */
@Schema(description = "A PRICAT line that could not be attached to a catalog product.")
public record UnmatchedPriceCatalogLineView(
        @Schema(description = "Identity of the quarantine row.", example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b")
        @NonNull
        UUID unmatchedLineId,

        @Schema(description = "Import that produced the line.", example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c")
        @NonNull
        UUID importManifestId,

        @Schema(
                description = "Vendor profile the catalog was fetched for.",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d")
        @NonNull
        UUID vendorProfileId,

        @Schema(description = "1-based line position in the vendor document.", example = "417") @Nullable
        Integer positionNumber,

        @Schema(description = "EAN the line carried.", example = "3528709999083") @Nullable
        String articleEan,

        @Schema(description = "Vendor article code; an alias, never used for matching.", example = "999908") @Nullable
        String supplierArticleCode,

        @Schema(description = "Cross-reference code the line carried.", example = "0123456789012") @Nullable
        String xReferenceCode,

        @Schema(
                description = "Why the line was quarantined.",
                example = "NO_CATALOG_MATCH",
                allowableValues = {
                    "NO_IDENTIFIER",
                    "NO_CATALOG_MATCH",
                    "AMBIGUOUS_CATALOG_MATCH",
                    "CATALOG_UNAVAILABLE",
                    "DUPLICATE_LINE",
                    "MALFORMED_LINE"
                })
        @NonNull
        String reason,

        @Schema(description = "Operator-facing detail for the reason.") @Nullable
        String reasonDetail,

        @Schema(description = "Net price the vendor stated.", example = "512.4000") @Nullable
        BigDecimal netPrice,

        @Schema(description = "Gross price the vendor stated.", example = "640.5000") @Nullable
        BigDecimal grossPrice,

        @Schema(description = "Inclusive first day the values would apply.", example = "2026-01-01") @Nullable
        LocalDate effectiveFrom,

        @Schema(description = "Currency of the stated amounts.", example = "SEK") @Nullable
        String currency,

        @Schema(description = "When the import fetched the line.") @NonNull
        Instant fetchedAt,

        @Schema(description = "When a later match closed the line; null while quarantined.") @Nullable
        Instant resolvedAt) {}
