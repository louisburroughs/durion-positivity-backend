package com.positivity.order.internal.dto;

import lombok.Data;

@Data
public class CancellationResponse {

    private String orderId;
    private String status;
    private String message;
    private String cancellationIdempotencyKey;
}
