package com.positivity.order.service;

import com.positivity.order.service.model.AddItemCommand;
import com.positivity.order.service.model.CheckoutResult;
import com.positivity.order.service.model.CreateCartCommand;
import com.positivity.order.service.model.CreateCartResult;
import com.positivity.order.service.model.OrderDiscountCommand;
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
    SalesOrderLineSummary addItem(@NonNull UUID orderId, @NonNull AddItemCommand command);

    @NonNull
    default SalesOrderLineSummary addItem(
            @NonNull UUID orderId,
            @NonNull String itemSku,
            int quantity,
            String reasonCode,
            BigDecimal manualPrice,
            UUID clientLineUuid) {
        return addItem(
                orderId, new AddItemCommand(itemSku, quantity, reasonCode, manualPrice, clientLineUuid, null, null));
    }

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

    /** Apply or replace the order-level discount (parity story B2, spec R3.10). */
    @NonNull
    SalesOrderSummary applyOrderDiscount(@NonNull UUID orderId, @NonNull OrderDiscountCommand command);

    /** Remove the order-level discount. */
    @NonNull
    SalesOrderSummary clearOrderDiscount(@NonNull UUID orderId);

    /** DRAFT → QUOTED: final reprice + tax, expiry set; counter quotes only (parity story A3). */
    @NonNull
    SalesOrderSummary quote(@NonNull UUID orderId);

    /** QUOTED → DRAFT: reopen for editing; best-effort reprice, tax marked stale. */
    @NonNull
    SalesOrderSummary reopenQuote(@NonNull UUID orderId);

    /**
     * DRAFT|QUOTED → PENDING_PAYMENT (parity story C1, spec R2.1): validates the cart, final
     * reprice + tax, freezes the lines, and creates the fronting invoice at pos-invoice.
     * Idempotent on the required {@code Idempotency-Key}; settlement completion is asynchronous
     * (story C3).
     */
    @NonNull
    CheckoutResult checkout(@NonNull UUID orderId, @NonNull String idempotencyKey);
}
