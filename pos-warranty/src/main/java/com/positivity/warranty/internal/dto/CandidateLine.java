package com.positivity.warranty.internal.dto;

import com.positivity.warranty.internal.enums.LineSourceType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One origin-line candidate from the cross-service invoice/workorder search (PRD §7 step 2,
 * {@code GET /v1/warranty/claims/candidate-lines}). The clerk picks the line(s) being claimed;
 * the chosen candidate seeds a {@code WarrantyClaimLine} (sourceType/sourceId/sourceLineId
 * provenance plus price/description snapshots).
 *
 * @param sourceType provenance kind ({@code INVOICE_LINE}, {@code WORKORDER_PART},
 *     {@code WORKORDER_SERVICE})
 * @param sourceId originating invoice or workorder id
 * @param sourceLineId originating invoice item / workorder line id
 * @param sourceReference human-readable invoice or workorder number
 * @param productEntityId pos-catalog product id when the source carries one (workorder parts)
 * @param sku catalog SKU when resolvable, else null
 * @param description line description snapshot
 * @param quantity source quantity (labor hours for service lines)
 * @param unitPrice source unit price (hourly rate for service lines)
 * @param lineTotal source line total
 * @param sourceCreatedAt when the source document was created (proxy for sale date)
 * @param photoEvidenceUrl photo captured on the workorder part, when present
 */
public record CandidateLine(
        LineSourceType sourceType,
        UUID sourceId,
        UUID sourceLineId,
        String sourceReference,
        UUID productEntityId,
        String sku,
        String description,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        Instant sourceCreatedAt,
        String photoEvidenceUrl) {}
