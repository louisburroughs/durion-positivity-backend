package com.positivity.order.internal.dto.purchaseorder;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a vendor says it can supply against a purchase order's lines (CAP-319 #1329).
 */
@Schema(
        name = "ProcurementAvailability",
        description = "Live vendor availability for a purchase order's lines. A supplier that could not be reached"
                + " is reported through the document status, never as zero quantities: availableQuantity is null"
                + " when the vendor stated nothing and zero only when it stated it has none, and only the second"
                + " means an article is unavailable.")
public record ProcurementAvailabilityResponse(
        @Schema(description = "The purchase order the lines belong to.")
        UUID purchaseOrderId,

        @Schema(description = "The vendor profile asked.") UUID vendorProfileId,

        @Schema(description = "The delivery location the answer is for; availability is consignee-specific.")
        UUID deliveryLocationId,

        @Schema(
                description = "Whether the vendor answered, and if not, why not: OK, SUPPLIER_UNAVAILABLE,"
                        + " NOT_LISTED, CAPABILITY_NOT_CONFIGURED or CONFIGURATION_ERROR. Anything other"
                        + " than OK means no line quantity here is a statement by the vendor.")
        String status,

        @Schema(description = "When the answer was obtained; null when there is no answer to date.") @Nullable
        Instant asOf,

        @Schema(description = "One entry per purchase-order line, in line-number order.")
        List<Line> lines) {

    /** One line's answer. */
    @Schema(
            name = "ProcurementAvailabilityLine",
            description = "One purchase-order line and what the vendor said about it.")
    public record Line(
            @Schema(description = "Purchase-order line id.") UUID lineId,

            @Schema(description = "Line number on the order.") @Nullable
            Integer lineNumber,

            @Schema(description = "Catalog product ordered.") @Nullable
            UUID skuId,

            @Schema(description = "The article code the vendor was asked about; null when none could be resolved.")
            @Nullable
            String articleEan,

            @Schema(description = "Quantity asked about: what is still outstanding on this line.")
            int requestedQuantity,

            @Schema(
                    description = "AVAILABLE, UNAVAILABLE, NOT_LISTED or NOT_ANSWERED as the vendor stated it;"
                            + " ARTICLE_NOT_IDENTIFIABLE when the line carries no code the vendor would"
                            + " recognise; NOTHING_OUTSTANDING when the line is already settled and so was not"
                            + " asked about. The last two describe our own situation, not the vendor's answer.")
            String status,

            @Schema(
                    description = "Quantity the vendor can supply. Null when the vendor stated no quantity;"
                            + " zero only when it stated it has none.")
            @Nullable
            Integer availableQuantity,

            @Schema(description = "Earliest date the vendor promised any quantity, when stated.") @Nullable
            LocalDate earliestDeliveryDate) {}
}
