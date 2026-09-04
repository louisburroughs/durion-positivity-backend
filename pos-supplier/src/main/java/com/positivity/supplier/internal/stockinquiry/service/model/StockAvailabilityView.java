package com.positivity.supplier.internal.stockinquiry.service.model;

import com.positivity.supplier.service.model.StockInquiryResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Product-keyed live availability across every enabled STOCK_INQUIRY vendor (#1637 decision 1).
 *
 * <p>Deliberately an internal view rather than a grant-surface DTO: no ADR names it for
 * cross-module use (ADR-0026 D2). It reuses the grant surface's {@link StockInquiryResponse.Status}
 * and {@link StockInquiryResponse.LineStatus} so the two reads degrade in the same vocabulary,
 * but its lines carry <strong>no article identifiers</strong>: which EAN, UPC or vendor article
 * code a product resolved to is this module's implementation detail, and exposing it would invite
 * the frontend to orchestrate per-vendor inquiries itself — the exact coupling decision 1 rules
 * out.
 *
 * <p>{@code fetchedAt} and {@code asOf} are two facts per vendor (#1637 decision 1): when this
 * module obtained the answer (cached answers keep their original fetch instant), and the
 * observation time the vendor stated (the fetch instant when it stated none, as on A2.5).
 * {@code stale} is judged from {@code asOf} against the echoed backend-owned threshold
 * (#1637 decision 3).
 *
 * @param productId the resolved catalog product — echoed even when the caller asked by SKU
 * @param deliveryLocationId the receiving location every vendor was asked about
 * @param requestedQuantity the quantity whose availability was checked
 * @param stalenessThreshold the effective backend-configured threshold, ISO-8601 duration
 * @param vendors one entry per enabled STOCK_INQUIRY vendor; empty when none is configured, which
 *     is a valid answer ("no vendor to ask"), not an error
 */
@Schema(
        name = "SupplierStockAvailability",
        description = "Live availability of one catalog product across every enabled stock-inquiry vendor, for one"
                + " receiving location. Partial by design: each vendor answers (or fails) independently, carried as a"
                + " per-vendor status. An empty vendors list means no vendor is configured for stock inquiry — a valid"
                + " answer, not an error.")
public record StockAvailabilityView(
        @Schema(description = "The resolved catalog product, echoed even when the caller asked by SKU.") @NonNull
        UUID productId,

        @Schema(description = "The receiving location every vendor was asked about.") @NonNull
        UUID deliveryLocationId,

        @Schema(description = "The quantity whose availability was checked.")
        int requestedQuantity,

        @Schema(
                description = "The backend-configured availability staleness threshold, as an ISO-8601 duration."
                        + " Echoed so every client judges freshness by the same rule; staleness is evaluated from"
                        + " each vendor's asOf.")
        @NonNull
        String stalenessThreshold,

        @Schema(description = "One entry per enabled stock-inquiry vendor; empty when none is configured.") @NonNull
        List<VendorAvailability> vendors) {

    public StockAvailabilityView {
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(deliveryLocationId, "deliveryLocationId must not be null");
        Objects.requireNonNull(stalenessThreshold, "stalenessThreshold must not be null");
        Objects.requireNonNull(vendors, "vendors must not be null");
        vendors = List.copyOf(vendors);
    }

    /**
     * One vendor's answer, or the reason it gave none.
     *
     * @param vendorProfileId the vendor profile asked
     * @param vendorDisplayName the profile's display name, for rendering
     * @param status whether this vendor answered, in the same vocabulary as the per-vendor inquiry
     * @param fetchedAt when this module obtained the answer; null when the vendor gave none
     * @param asOf the vendor's stated observation instant (the fetch instant when it stated none);
     *     null when the vendor gave no answer
     * @param stale whether {@code asOf} is older than the echoed threshold; null exactly when
     *     {@code asOf} is null — an unanswered vendor has no freshness to judge
     * @param lines the vendor's per-article answers; empty unless the vendor answered
     */
    @Schema(
            name = "SupplierStockAvailabilityVendor",
            description = "One vendor's live answer about the product, or the reason it gave none. fetchedAt is when"
                    + " this platform obtained the answer (a cached answer keeps its original fetch instant); asOf is"
                    + " the observation time the vendor stated, and staleness is judged from asOf alone.")
    public record VendorAvailability(
            @Schema(description = "The vendor profile that was asked.") @NonNull
            UUID vendorProfileId,

            @Schema(description = "The vendor profile's display name.") @NonNull
            String vendorDisplayName,

            @Schema(
                    description = "Whether this vendor answered: OK and NOT_LISTED are answers;"
                            + " SUPPLIER_UNAVAILABLE covers failure AND a vendor that had not answered by the"
                            + " fan-out deadline; CAPABILITY_NOT_CONFIGURED and CONFIGURATION_ERROR are"
                            + " deployment states.")
            StockInquiryResponse.@NonNull Status status,

            @Schema(
                    description = "When this platform obtained the answer. A cached answer carries the instant of"
                            + " the original vendor call, not of the cache hit. Null when the vendor gave no"
                            + " answer.")
            @Nullable
            Instant fetchedAt,

            @Schema(
                    description = "The observation instant the vendor stated for its answer; the fetch instant when"
                            + " the vendor norm states none. Null when the vendor gave no answer. This is a"
                            + " different fact from fetchedAt.")
            @Nullable
            Instant asOf,

            @Schema(
                    description = "Whether asOf is older than the echoed stalenessThreshold. Null exactly when asOf"
                            + " is null.")
            @Nullable
            Boolean stale,

            @Schema(description = "Per-article answers; empty unless the vendor answered.") @NonNull
            List<Line> lines) {

        // Left as IllegalArgumentException (#1694): this is a response view built server-side from
        // vendor answers/cache state, never from client input. A violation here is this module's
        // own defect, so it belongs on the platform 500 fallback, not a client 4xx.
        public VendorAvailability {
            Objects.requireNonNull(vendorProfileId, "vendorProfileId must not be null");
            Objects.requireNonNull(vendorDisplayName, "vendorDisplayName must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(lines, "lines must not be null");
            lines = List.copyOf(lines);
            if ((asOf == null) != (stale == null)) {
                throw new IllegalArgumentException("stale must be present exactly when asOf is");
            }
        }
    }

    /**
     * One per-article answer, stripped of article identifiers: which codes were used to ask is an
     * implementation detail (#1637 decision 1).
     *
     * @param status what the vendor said about the article
     * @param availableQuantity the canonical item/piece count the vendor can supply (the A2.5
     *     quantity unit); null when the vendor stated none, zero only for UNAVAILABLE
     * @param earliestDeliveryDate earliest date the vendor promised any quantity, when stated
     * @param quotedUnitPrice quoted unit price, when the vendor norm carries one (A2.5 does not)
     * @param currency ISO 4217 currency of the quoted price, when quoted
     */
    @Schema(
            name = "SupplierStockAvailabilityLine",
            description = "One vendor's answer about the product. Carries no EAN/UPC/vendor article codes — product"
                    + " identity mapping is a backend implementation detail. availableQuantity is the canonical"
                    + " item/piece count: no unit of measure travels because none exists in the supplier wire data.")
    public record Line(
            @Schema(description = "What the vendor said about the article.")
            StockInquiryResponse.@NonNull LineStatus status,

            @Schema(
                    description = "Quantity the vendor can supply, as a canonical item/piece count. Null when the"
                            + " vendor stated no quantity; zero only when the status is UNAVAILABLE, meaning the"
                            + " vendor stated it has none.")
            @Nullable
            Integer availableQuantity,

            @Schema(description = "Earliest date the vendor promised any quantity, when stated.") @Nullable
            LocalDate earliestDeliveryDate,

            @Schema(description = "Quoted unit price, when the vendor norm carries one.") @Nullable
            BigDecimal quotedUnitPrice,

            @Schema(description = "ISO 4217 currency of the quoted price, when quoted.") @Nullable
            String currency) {

        // Left as IllegalArgumentException (#1694): this is a response view built server-side from
        // vendor answers, never from client input. A violation here is this module's own defect,
        // so it belongs on the platform 500 fallback, not a client 4xx.
        public Line {
            Objects.requireNonNull(status, "status must not be null");
            if (status != StockInquiryResponse.LineStatus.AVAILABLE
                    && status != StockInquiryResponse.LineStatus.UNAVAILABLE
                    && availableQuantity != null) {
                throw new IllegalArgumentException(
                        "a quantity may only accompany an answered line, but status was " + status);
            }
        }
    }
}
