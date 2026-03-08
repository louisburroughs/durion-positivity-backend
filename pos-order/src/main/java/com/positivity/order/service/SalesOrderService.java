package com.positivity.order.service;

import com.positivity.order.service.model.SalesOrderLineSummary;
import com.positivity.order.service.model.SalesOrderSummary;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface SalesOrderService {

    @NonNull
    SalesOrderSummary createCart(@NonNull String clerkId, @NonNull String terminalId, String customerId,
            String vehicleId);

    @NonNull
    SalesOrderLineSummary addItem(@NonNull UUID orderId, @NonNull String itemSku, int quantity, String reasonCode,
            BigDecimal manualPrice);

    @NonNull
    default SalesOrderLineSummary addItem(@NonNull UUID orderId, @NonNull String itemSku, int quantity,
            String reasonCode) {
        return addItem(orderId, itemSku, quantity, reasonCode, null);
    }

    @NonNull
    SalesOrderLineSummary updateItemQuantity(@NonNull UUID orderId, @NonNull UUID lineId, int newQuantity);

    void removeItem(@NonNull UUID orderId, @NonNull UUID lineId);

    @NonNull
    SalesOrderSummary getOrder(@NonNull UUID orderId);

    @NonNull
    SalesOrderSummary linkSource(@NonNull UUID orderId, @NonNull String sourceType, @NonNull String sourceId);
}
