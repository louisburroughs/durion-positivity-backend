package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Aggregated product detail view combining catalog, pricing, and inventory data.
 * Implements graceful degradation with field-level status indicators.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Consolidated product detail view with pricing and availability")
public class ProductDetailView {

    @Schema(
            description = "Unique product identifier",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = REQUIRED)
    private String productId;

    @Schema(description = "Product description", example = "Heavy Duty Wrench", requiredMode = NOT_REQUIRED)
    private String description;

    @Schema(description = "Product specifications", example = "[]", requiredMode = NOT_REQUIRED)
    private List<ProductSpecification> specifications;

    @Schema(description = "Pricing information with status indicator", requiredMode = NOT_REQUIRED)
    private PricingInfo pricing;

    @Schema(description = "Availability information with status indicator", requiredMode = NOT_REQUIRED)
    private AvailabilityInfo availability;

    @Schema(
            description = "Live vendor stock for this product, when the deployment has a supplier integration"
                    + " configured. Absent otherwise — its absence means the question was never asked, not that a"
                    + " vendor has none.",
            requiredMode = NOT_REQUIRED)
    private SupplierAvailabilityInfo supplierAvailability;

    @Schema(description = "Substitution product suggestions", example = "[]", requiredMode = NOT_REQUIRED)
    private List<SubstitutionHint> substitutions;

    @Schema(
            description = "Timestamp when this response was generated",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    private Instant generatedAt;

    @Schema(description = "Overall confidence in the data completeness", example = "HIGH", requiredMode = REQUIRED)
    private DataConfidence confidence;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Product specification entry")
    public static class ProductSpecification {
        @Schema(description = "Specification name", example = "Material", requiredMode = REQUIRED)
        private String name;

        @Schema(description = "Specification value", example = "Steel", requiredMode = REQUIRED)
        private String value;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Pricing information with availability status")
    public static class PricingInfo {
        @Schema(description = "Manufacturer's suggested retail price", example = "99.99", requiredMode = NOT_REQUIRED)
        private Double msrp;

        @Schema(description = "Store-specific price", example = "89.99", requiredMode = NOT_REQUIRED)
        private Double storePrice;

        @Schema(description = "Currency code", example = "USD", requiredMode = NOT_REQUIRED)
        private String currency;

        @Schema(description = "Pricing data status", example = "OK", requiredMode = REQUIRED)
        private DataStatus status;

        @Schema(
                description = "Timestamp of pricing data",
                example = "2026-01-15T09:30:00Z",
                requiredMode = NOT_REQUIRED)
        private Instant asOf;

        @Schema(description = "Confidence level in pricing data", example = "HIGH", requiredMode = REQUIRED)
        private DataConfidence confidence;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Availability information with status indicator")
    public static class AvailabilityInfo {
        @Schema(description = "On-hand quantity at location", example = "15", requiredMode = NOT_REQUIRED)
        private BigDecimal onHandQuantity;

        @Schema(description = "Available to promise quantity", example = "12", requiredMode = NOT_REQUIRED)
        private BigDecimal availableToPromiseQuantity;

        @Schema(description = "Lead time information", requiredMode = NOT_REQUIRED)
        private LeadTimeInfo leadTime;

        @Schema(description = "Availability data status", example = "OK", requiredMode = REQUIRED)
        private DataStatus status;

        @Schema(
                description = "Timestamp of availability data",
                example = "2026-01-15T09:30:00Z",
                requiredMode = NOT_REQUIRED)
        private Instant asOf;

        @Schema(description = "Confidence level in availability data", example = "MEDIUM", requiredMode = REQUIRED)
        private DataConfidence confidence;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Lead time information with source indicator")
    public static class LeadTimeInfo {
        @Schema(description = "Source of lead time data", example = "CATALOG", requiredMode = REQUIRED)
        private LeadTimeSource source;

        @Schema(description = "Minimum days for delivery", example = "2", requiredMode = NOT_REQUIRED)
        private Integer minDays;

        @Schema(description = "Maximum days for delivery", example = "5", requiredMode = NOT_REQUIRED)
        private Integer maxDays;

        @Schema(
                description = "Human-readable lead time hint",
                example = "3-5 business days",
                requiredMode = NOT_REQUIRED)
        private String displayText;

        @Schema(
                description = "Timestamp of lead time data",
                example = "2026-01-15T09:30:00Z",
                requiredMode = NOT_REQUIRED)
        private Instant asOf;

        @Schema(description = "Confidence in lead time estimate", example = "LOW", requiredMode = REQUIRED)
        private DataConfidence confidence;
    }

    /**
     * Live vendor stock, asked of the supplier at request time (CAP-319 #1225).
     *
     * <p>The only component here whose data is not ours and cannot be replicated: vendor stock lives
     * at the vendor, changes without telling us, and is worthless once stale. That is why it is
     * fetched synchronously under the ADR-0044 amendment while every other cross-domain read on this
     * page comes from an event-fed replica.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(
            description = "Live vendor stock with a status indicator. A null quantity means the vendor stated"
                    + " nothing; zero means the vendor stated it has none, and only that justifies showing a"
                    + " customer that the vendor is out of stock.")
    public static class SupplierAvailabilityInfo {

        @Schema(
                description = "Quantity the vendor can supply. Null whenever the status is not OK, and null even"
                        + " under OK when the vendor answered without stating a quantity.",
                example = "8",
                requiredMode = NOT_REQUIRED)
        private Integer availableQuantity;

        @Schema(
                description = "Earliest date the vendor promised any quantity, when it stated one.",
                example = "2026-08-20",
                requiredMode = NOT_REQUIRED)
        private LocalDate earliestDeliveryDate;

        @Schema(
                description = "Whether the vendor carries this product at all. NOT_LISTED is a definite answer;"
                        + " UNAVAILABLE means the vendor holds none right now.",
                example = "AVAILABLE",
                requiredMode = NOT_REQUIRED)
        private SupplierStockStatus vendorStatus;

        @Schema(description = "Supplier data status", example = "OK", requiredMode = REQUIRED)
        private DataStatus status;

        @Schema(
                description = "When the supplier fact was obtained. Present only when the vendor answered — a"
                        + " degraded component carries no timestamp, because there is no vendor statement to date."
                        + " A cached answer carries the age of the fact, not of the cache hit.",
                example = "2026-08-14T12:00:00Z",
                requiredMode = NOT_REQUIRED)
        private Instant asOf;

        @Schema(description = "Confidence in the supplier data", example = "HIGH", requiredMode = REQUIRED)
        private DataConfidence confidence;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Product substitution suggestion")
    public static class SubstitutionHint {
        @Schema(
                description = "Substitute product ID",
                example = "650e8400-e29b-41d4-a716-446655440001",
                requiredMode = REQUIRED)
        private String productId;

        @Schema(description = "Reason for substitution", example = "Newer Model", requiredMode = NOT_REQUIRED)
        private String reason;
    }

    @Schema(description = "Data status indicator")
    public enum DataStatus {
        OK,
        UNAVAILABLE,
        STALE,
        ERROR
    }

    @Schema(description = "Lead time data source")
    public enum LeadTimeSource {
        CATALOG,
        INVENTORY,
        SUPPLY_CHAIN
    }

    /** What a vendor said about an article, as reported by the supplier service. */
    @Schema(description = "The vendor's answer about this article")
    public enum SupplierStockStatus {
        /** The vendor stated an availability. */
        AVAILABLE,
        /** The vendor stated it has none — an answer, not a silence. */
        UNAVAILABLE,
        /** The vendor does not carry, or will not sell, this article. */
        NOT_LISTED,
        /** The vendor returned the article without stating an availability. */
        NOT_ANSWERED
    }

    @Schema(description = "Data confidence level")
    public enum DataConfidence {
        LOW,
        MEDIUM,
        HIGH
    }
}
