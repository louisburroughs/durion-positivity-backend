package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.purchaseorder.ApprovePurchaseOrderRequest;
import com.positivity.inventory.internal.dto.purchaseorder.CreatePurchaseOrderRequest;
import com.positivity.inventory.internal.dto.purchaseorder.ListPurchaseOrdersRequest;
import com.positivity.inventory.internal.dto.purchaseorder.PurchaseOrderResponse;
import com.positivity.inventory.internal.dto.purchaseorder.ReceivePurchaseOrderRequest;
import com.positivity.inventory.internal.dto.purchaseorder.ReceivePurchaseOrderResponse;
import com.positivity.inventory.internal.dto.purchaseorder.RevisePurchaseOrderRequest;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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

    @NonNull
    ReceivePurchaseOrderResponse receivePurchaseOrder(
            @NonNull UUID poId, @NonNull ReceivePurchaseOrderRequest request, @NonNull String actorId);
}
