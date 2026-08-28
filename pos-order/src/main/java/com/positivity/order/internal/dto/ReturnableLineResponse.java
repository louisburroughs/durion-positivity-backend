package com.positivity.order.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Per-line returnable remainder for a completed order")
public record ReturnableLineResponse(
        UUID orderLineId, String itemSku, int soldQty, int alreadyReturned, int returnableQty, boolean returnable) {

    public static ReturnableLineResponse from(ReturnableLineView v) {
        return new ReturnableLineResponse(
                v.orderLineId(), v.itemSku(), v.soldQty(), v.alreadyReturned(), v.returnableQty(), v.returnable());
    }
}
