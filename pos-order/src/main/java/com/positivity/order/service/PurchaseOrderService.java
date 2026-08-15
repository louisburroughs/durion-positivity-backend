package com.positivity.order.service;

import com.positivity.order.internal.dto.purchaseorder.ApprovePurchaseOrderRequest;
import com.positivity.order.internal.dto.purchaseorder.CreatePurchaseOrderRequest;
import com.positivity.order.internal.dto.purchaseorder.ListPurchaseOrdersRequest;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderResponse;
import com.positivity.order.internal.dto.purchaseorder.RevisePurchaseOrderRequest;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * The commercial purchase order: ordering, approval, revision and cancellation (CAP-320 #1334,
 * ADR-0049 §2).
 *
 * <h2>What is deliberately absent</h2>
 *
 * There is no {@code receivePurchaseOrder}. Receiving is pos-inventory's work — it is what
 * inspects, counts, posts to the ledger and captures lots — and what arrives is reported back as
 * a fact rather than written into the order by a caller. That keeps one writer for the order and
 * one writer for stock, which is the whole point of the split: a receive method here would be a
 * second way to move open quantities, and the second way always finds a caller.
 */
public interface PurchaseOrderService {

    @NonNull
    PurchaseOrderResponse createPurchaseOrder(@NonNull CreatePurchaseOrderRequest request, @NonNull String actorId);

    @NonNull
    PurchaseOrderResponse getPurchaseOrder(@NonNull UUID poId);

    @NonNull
    Page<PurchaseOrderResponse> listPurchaseOrders(
            @NonNull ListPurchaseOrdersRequest filter, @NonNull Pageable pageable);

    @NonNull
    PurchaseOrderResponse approvePurchaseOrder(
            @NonNull UUID poId, @NonNull ApprovePurchaseOrderRequest request, @NonNull String actorId);

    @NonNull
    PurchaseOrderResponse revisePurchaseOrder(
            @NonNull UUID poId, @NonNull RevisePurchaseOrderRequest request, @NonNull String actorId);

    @NonNull
    PurchaseOrderResponse cancelPurchaseOrder(@NonNull UUID poId, @NonNull String actorId);
}
