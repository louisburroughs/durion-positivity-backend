package com.positivity.inventory.internal.dto.traceability;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One upstream source-document reference in a lot/serial traceability response (odoo-parity E5,
 * issue #1051): the purchase order, ASN, or goods receipt that brought the lot/serial into stock.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "An upstream source-document reference (PO / ASN / goods receipt)")
public class TraceabilityDocumentRef {

    @Schema(
            description = "Kind of source document",
            example = "PURCHASE_ORDER",
            allowableValues = {"PURCHASE_ORDER", "ASN", "GOODS_RECEIPT"},
            requiredMode = REQUIRED)
    private String documentType;

    @Schema(description = "Identifier of the source document", requiredMode = REQUIRED)
    private UUID documentId;

    @Schema(
            description = "Human-readable document reference (PO number, ASN reference, receipt number)",
            example = "PO-000123",
            requiredMode = NOT_REQUIRED)
    private String reference;
}
