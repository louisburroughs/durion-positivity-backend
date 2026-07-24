package com.positivity.inventory.internal.dto.traceability;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Upstream origin of a lot/serial in a traceability response (odoo-parity E5, issue #1051): where
 * the stock came from — the vendor, the first receipt, and the PO/ASN/goods-receipt documents.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Upstream origin: vendor, receipt date, and source documents (PO / ASN / goods receipt)")
public class TraceabilityUpstream {

    @Schema(description = "Vendor the lot/serial was first received from, when known", requiredMode = NOT_REQUIRED)
    private UUID vendorId;

    @Schema(description = "When the lot/serial was first received into stock", requiredMode = NOT_REQUIRED)
    private Instant receivedAt;

    @Schema(description = "Upstream source documents, PO first then ASN then goods receipt", requiredMode = REQUIRED)
    @Builder.Default
    private List<TraceabilityDocumentRef> documents = List.of();
}
