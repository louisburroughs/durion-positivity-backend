package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One vendor price for a product in a market, as at one effective date (ADR-0053 §2).
 *
 * <p>Purchasing and margin context only. Nothing that resolves a sell price may read this
 * (ADR-0053 §4, ADR-0054); {@code suggestedRetailPrice} is vendor reference data and must be
 * labelled with its PRICAT source wherever it is displayed, never applied as a price.
 *
 * <p>Null money fields mean the vendor did not state the value. They do not mean zero — a consumer
 * that coerces them turns an unstated net price into a free product.
 */
@Schema(description = "A vendor price for a product in one market, effective from a date.")
public record SupplierPriceEntryDto(
        @Schema(description = "Identity of this price entry.", example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b")
        @NonNull
        UUID id,

        @Schema(
                description = "Vendor profile that supplied the price.",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c")
        @NonNull
        UUID vendorProfileId,

        @Schema(description = "Vendor profile alias at import time; descriptive only.", example = "michelin-eu")
        @NonNull
        String supplierRef,

        @Schema(description = "Product the price applies to.", example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d")
        @NonNull
        UUID productId,

        @Schema(
                description = "Which identifier matched the vendor line to the product.",
                example = "EAN",
                allowableValues = {"EAN", "UPC_XREFERENCE"})
        @NonNull
        String matchMethod,

        @Schema(description = "EAN the vendor stated.", example = "3528709999083") @Nullable
        String articleEan,

        @Schema(description = "Vendor article code; display alias, never an identifier.", example = "999908") @Nullable
        String supplierArticleCode,

        @Schema(description = "Buyer account the price was quoted for.", example = "30012456") @NonNull
        String buyerAccountNumber,

        @Schema(description = "Market the price applies to.", example = "SE") @NonNull
        String countryCode,

        @Schema(description = "Currency of every amount here.", example = "SEK") @NonNull
        String currency,

        @Schema(description = "Vendor-suggested retail price; reference only, never an applied sell price.") @Nullable
        BigDecimal suggestedRetailPrice,

        @Schema(description = "Gross (list) purchase price as stated.", example = "2895.5000") @Nullable
        BigDecimal grossPrice,

        @Schema(description = "Net purchase price as stated.", example = "2450.2500") @Nullable
        BigDecimal netPrice,

        @Schema(description = "Tax rate the vendor stated for the line.", example = "25.0000") @Nullable
        BigDecimal taxRate,

        @Schema(description = "Recycling fee the vendor stated.", example = "35.0000") @Nullable
        BigDecimal recyclingFee,

        @Schema(description = "Inclusive first day the values apply; a vendor-local date.", example = "2026-02-01")
        @NonNull
        LocalDate effectiveFrom,

        @Schema(description = "Vendor document the price came from.", example = "PRICAT-4046266_202305120844") @Nullable
        String sourceDocumentId,

        @Schema(description = "Vendor document date.", example = "2026-05-12") @Nullable
        LocalDate sourceDocumentDate,

        @Schema(description = "Import that produced this entry.", example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5e")
        @NonNull
        UUID importManifestId,

        @Schema(description = "When the producer fetched the document.") @NonNull
        Instant fetchedAt) {}
