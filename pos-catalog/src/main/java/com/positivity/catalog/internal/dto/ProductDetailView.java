package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
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

    @Schema(description = "Unique product identifier", example = "550e8400-e29b-41d4-a716-446655440000")
    private String productId;

    @Schema(description = "Product description", example = "Heavy Duty Wrench")
    private String description;

    @Schema(description = "Product specifications")
    private List<ProductSpecification> specifications;

    @Schema(description = "Pricing information with status indicator")
    private PricingInfo pricing;

    @Schema(description = "Availability information with status indicator")
    private AvailabilityInfo availability;

    @Schema(description = "Substitution product suggestions")
    private List<SubstitutionHint> substitutions;

    @Schema(description = "Timestamp when this response was generated")
    private Instant generatedAt;

    @Schema(description = "Overall confidence in the data completeness", example = "HIGH")
    private DataConfidence confidence;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Product specification entry")
    public static class ProductSpecification {
        @Schema(description = "Specification name", example = "Material")
        private String name;

        @Schema(description = "Specification value", example = "Steel")
        private String value;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Pricing information with availability status")
    public static class PricingInfo {
        @Schema(description = "Manufacturer's suggested retail price", example = "99.99")
        private Double msrp;

        @Schema(description = "Store-specific price", example = "89.99")
        private Double storePrice;

        @Schema(description = "Currency code", example = "USD")
        private String currency;

        @Schema(description = "Pricing data status", example = "OK")
        private DataStatus status;

        @Schema(description = "Timestamp of pricing data")
        private Instant asOf;

        @Schema(description = "Confidence level in pricing data", example = "HIGH")
        private DataConfidence confidence;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Availability information with status indicator")
    public static class AvailabilityInfo {
        @Schema(description = "On-hand quantity at location", example = "15")
        private Integer onHandQuantity;

        @Schema(description = "Available to promise quantity", example = "12")
        private Integer availableToPromiseQuantity;

        @Schema(description = "Lead time information")
        private LeadTimeInfo leadTime;

        @Schema(description = "Availability data status", example = "OK")
        private DataStatus status;

        @Schema(description = "Timestamp of availability data")
        private Instant asOf;

        @Schema(description = "Confidence level in availability data", example = "MEDIUM")
        private DataConfidence confidence;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Lead time information with source indicator")
    public static class LeadTimeInfo {
        @Schema(description = "Source of lead time data", example = "CATALOG")
        private LeadTimeSource source;

        @Schema(description = "Minimum days for delivery", example = "2")
        private Integer minDays;

        @Schema(description = "Maximum days for delivery", example = "5")
        private Integer maxDays;

        @Schema(description = "Human-readable lead time hint", example = "3-5 business days")
        private String displayText;

        @Schema(description = "Timestamp of lead time data")
        private Instant asOf;

        @Schema(description = "Confidence in lead time estimate", example = "LOW")
        private DataConfidence confidence;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Product substitution suggestion")
    public static class SubstitutionHint {
        @Schema(description = "Substitute product ID", example = "650e8400-e29b-41d4-a716-446655440001")
        private String productId;

        @Schema(description = "Reason for substitution", example = "Newer Model")
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

    @Schema(description = "Data confidence level")
    public enum DataConfidence {
        LOW,
        MEDIUM,
        HIGH
    }
}
