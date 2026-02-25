package com.positivity.inventory.internal.dto;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/**
 * Response returned when an inventory adjustment request is created.
 *
 * Issue: CAP-215 Story #37
 */
@Value
@Builder
public class AdjustmentRequestResponse {
    UUID adjustmentRequestId;
    String productSku;
    UUID locationId;
    Integer quantity;
    String reasonCode;
    String status;
}
