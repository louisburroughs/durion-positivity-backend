package com.positivity.order.internal.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesOrderLineResponse {

    private String orderLineId;
    private String itemSku;
    private String itemDescription;
    private int quantity;
    private BigDecimal unitPrice;
    private String fulfillmentStatus;
    private String priceSource;
    private String reasonCode;
    private String sourceType;
    private String sourceId;
    private String sourceLineId;
}
