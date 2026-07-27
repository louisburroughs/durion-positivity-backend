package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.enums.AsnStatus;
import com.positivity.inventory.internal.enums.PurchaseOrderStatus;
import com.positivity.inventory.internal.enums.TransferOrderStatus;
import com.positivity.inventory.internal.repository.AsnLineRepository;
import com.positivity.inventory.internal.repository.PurchaseOrderLineRepository;
import com.positivity.inventory.internal.repository.TransferOrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes open expected supply from purchase-order and ASN documents (odoo-parity A2, issue
 * #1028). See {@link ExpectedSupplyService} for the source and horizon semantics.
 */
@Service
@RequiredArgsConstructor
public class ExpectedSupplyServiceImpl implements ExpectedSupplyService {

    /** PO statuses whose open line quantity counts as expected supply. */
    private static final List<PurchaseOrderStatus> OPEN_PO_STATUSES =
            List.of(PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.PARTIALLY_RECEIVED);

    /** ASN statuses whose un-received remainder counts as expected supply. */
    private static final List<AsnStatus> OPEN_ASN_STATUSES =
            List.of(AsnStatus.LOADED, AsnStatus.READY_FOR_RECEIPT, AsnStatus.PARTIALLY_RECEIVED);

    /** Transfer-order statuses with an in-transit remainder inbound to the destination (C2, #1036). */
    private static final List<TransferOrderStatus> IN_TRANSIT_TRANSFER_STATUSES =
            List.of(TransferOrderStatus.DISPATCHED, TransferOrderStatus.PARTIALLY_RECEIVED);

    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final AsnLineRepository asnLineRepository;
    private final TransferOrderRepository transferOrderRepository;

    @Override
    @Transactional(readOnly = true)
    public @NonNull BigDecimal expectedIncomingQuantity(
            @NonNull String stockItemId, @Nullable UUID siteId, @Nullable Instant horizon) {
        boolean bounded = horizon != null;
        LocalDate horizonDate = bounded ? horizon.atOffset(ZoneOffset.UTC).toLocalDate() : null;

        BigDecimal poOpen = BigDecimal.ZERO;
        UUID skuUuid = parseUuid(stockItemId);
        if (skuUuid != null) {
            BigDecimal sum = purchaseOrderLineRepository.sumOpenQuantityForSku(
                    skuUuid, OPEN_PO_STATUSES, siteId, bounded, horizonDate);
            poOpen = sum == null ? BigDecimal.ZERO : sum;
        }

        BigDecimal asnRemainder = asnLineRepository.sumUnreceivedRemainderForSku(
                stockItemId, OPEN_ASN_STATUSES, siteId, bounded, horizonDate);
        if (asnRemainder == null) {
            asnRemainder = BigDecimal.ZERO;
        }

        // Inbound in-transit transfers (odoo-parity C2, #1036): dispatched − received toward
        // the destination site. Included under ANY horizon: unlike undated PO/ASN supply the
        // goods have physically shipped, so they are the most certain supply in the sum.
        BigDecimal inTransit = BigDecimal.valueOf(
                transferOrderRepository.sumInboundInTransitForSku(stockItemId, IN_TRANSIT_TRANSFER_STATUSES, siteId));

        return poOpen.add(asnRemainder).add(inTransit);
    }

    private static @Nullable UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
