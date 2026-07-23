package com.positivity.order.service;

import com.positivity.order.service.model.CreateCartCommand;
import com.positivity.order.service.model.CreateCartResult;
import com.positivity.order.service.model.SalesOrderLineSummary;
import com.positivity.order.service.model.SalesOrderSummary;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface SalesOrderService {

    @NonNull
    CreateCartResult createCart(@NonNull CreateCartCommand command);

    @NonNull
    SalesOrderLineSummary addItem(
            @NonNull UUID orderId,
            @NonNull String itemSku,
            int quantity,
            String reasonCode,
            BigDecimal manualPrice,
            UUID clientLineUuid);

    @NonNull
    default SalesOrderLineSummary addItem(
            @NonNull UUID orderId, @NonNull String itemSku, int quantity, String reasonCode, BigDecimal manualPrice) {
        return addItem(orderId, itemSku, quantity, reasonCode, manualPrice, null);
    }

    @NonNull
    default SalesOrderLineSummary addItem(
            @NonNull UUID orderId, @NonNull String itemSku, int quantity, String reasonCode) {
        return addItem(orderId, itemSku, quantity, reasonCode, null, null);
    }

    @NonNull
    SalesOrderLineSummary updateItemQuantity(@NonNull UUID orderId, @NonNull UUID lineId, int newQuantity);

    void removeItem(@NonNull UUID orderId, @NonNull UUID lineId);

    @NonNull
    SalesOrderSummary getOrder(@NonNull UUID orderId);

    /** List carts for parking/resume flows (plan story A2); lines are omitted from list results. */
    @NonNull
    List<SalesOrderSummary> listCarts(String clerkId, String terminalId, String status, int page, int size);

    @NonNull
    SalesOrderSummary linkSource(@NonNull UUID orderId, @NonNull String sourceType, @NonNull String sourceId);
}
