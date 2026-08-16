package com.positivity.order.internal.service;

import com.positivity.order.internal.client.SupplierStockClient;
import com.positivity.order.internal.dto.purchaseorder.ProcurementAvailabilityResponse;
import com.positivity.order.internal.entity.ExtProductCode;
import com.positivity.order.internal.entity.PurchaseOrderEntity;
import com.positivity.order.internal.entity.PurchaseOrderLineEntity;
import com.positivity.order.internal.exception.PurchaseOrderNotFoundException;
import com.positivity.order.internal.repository.ExtProductCodeRepository;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a vendor can actually supply against the lines of a purchase order (CAP-319 #1329).
 *
 * <h2>The question a buyer is asking</h2>
 *
 * Not "is this in stock" but "if I order these quantities from this vendor to this place, what
 * turns up and when". That shapes three things: the delivery location is required rather than
 * defaulted, because availability is consignee-specific and answering for the wrong warehouse
 * answers a different question; the whole order goes in one inquiry, because a buyer comparing ten
 * lines should not cost ten vendor round trips; and a line nobody could ask about is reported as
 * such rather than folded in with the ones the vendor declined.
 *
 * <h2>Unknown is not zero</h2>
 *
 * The distinction is carried from the vendor's answer all the way to the response and never
 * collapsed. A null quantity means the vendor stated nothing; zero means it stated it has none.
 * Only the second may be shown to a buyer as "none available" — the first means "we do not know",
 * and a buyer who cannot tell them apart will either order blind or not order at all.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcurementAvailabilityService {

    /** The code type a vendor recognises as an article identifier. */
    private static final String EAN = "EAN";

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final ExtProductCodeRepository extProductCodeRepository;
    private final SupplierStockClient supplierStockClient;

    /**
     * Availability for every line of one purchase order.
     *
     * @param deliveryLocationId where the goods would go — required by the caller, never taken from
     *     the order. The buyer may well be pricing a delivery to somewhere other than the order's
     *     own ship-to, and silently substituting one would answer confidently about the wrong place.
     */
    @Transactional(readOnly = true)
    public @NonNull ProcurementAvailabilityResponse availabilityFor(
            @NonNull UUID purchaseOrderId, @NonNull UUID vendorProfileId, @NonNull UUID deliveryLocationId) {
        PurchaseOrderEntity order = purchaseOrderRepository
                .findById(purchaseOrderId)
                .orElseThrow(() -> new PurchaseOrderNotFoundException(purchaseOrderId));

        List<PurchaseOrderLineEntity> orderedLines = order.getLines().stream()
                .sorted(Comparator.comparing(
                        PurchaseOrderLineEntity::getLineNumber, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        // Resolve every line's article once and keep the answer. Looking it up again while
        // rendering would double the reads and, worse, could disagree with itself if the replica
        // changed mid-request — a line grouped under one article and reported under another.
        Map<UUID, String> eanByLineId = new HashMap<>();
        Map<String, List<PurchaseOrderLineEntity>> linesByEan = new HashMap<>();
        List<PurchaseOrderLineEntity> unidentifiable = new ArrayList<>();
        for (PurchaseOrderLineEntity line : orderedLines) {
            String ean = eanFor(line);
            if (ean == null) {
                unidentifiable.add(line);
                continue;
            }
            eanByLineId.put(line.getLineId(), ean);
            // A line with nothing outstanding is settled and needs no answer. It must also be kept
            // out of the inquiry: the supplier contract rejects a quantity below one, so a single
            // fully received line would 400 the request and degrade the whole screen to
            // "supplier unavailable" — one settled line hiding every other line's answer.
            if (requestedQuantity(line) > 0) {
                linesByEan.computeIfAbsent(ean, k -> new ArrayList<>()).add(line);
            }
        }

        // One inquiry for the whole order, not one per line.
        List<SupplierStockClient.InquiryLine> inquiryLines = linesByEan.entrySet().stream()
                .map(entry -> new SupplierStockClient.InquiryLine(
                        entry.getKey(),
                        null,
                        entry.getValue().stream()
                                .mapToInt(ProcurementAvailabilityService::requestedQuantity)
                                .sum()))
                .toList();

        SupplierStockClient.StockAnswer answer =
                supplierStockClient.inquire(vendorProfileId, deliveryLocationId, inquiryLines);

        Map<String, SupplierStockClient.AnswerLine> answersByEan = new HashMap<>();
        for (SupplierStockClient.AnswerLine answerLine : answer.lines()) {
            if (answerLine.articleEan() != null) {
                answersByEan.put(answerLine.articleEan(), answerLine);
            }
        }

        List<ProcurementAvailabilityResponse.Line> lines = new ArrayList<>();
        for (PurchaseOrderLineEntity line : orderedLines) {
            lines.add(toResponseLine(line, eanByLineId.get(line.getLineId()), answersByEan));
        }

        if (!unidentifiable.isEmpty()) {
            log.debug(
                    "Purchase order {}: {} line(s) carry no article code the vendor would recognise",
                    purchaseOrderId,
                    unidentifiable.size());
        }

        return new ProcurementAvailabilityResponse(
                purchaseOrderId,
                vendorProfileId,
                deliveryLocationId,
                answer.status().name(),
                answer.asOf(),
                lines);
    }

    private ProcurementAvailabilityResponse.Line toResponseLine(
            PurchaseOrderLineEntity line, @Nullable String ean, Map<String, SupplierStockClient.AnswerLine> answers) {
        int requested = requestedQuantity(line);

        if (ean == null) {
            // No shared name for this article, so the vendor was never asked about it. Reporting it
            // as unanswered would suggest the vendor declined to say; it never had the chance.
            return new ProcurementAvailabilityResponse.Line(
                    line.getLineId(),
                    line.getLineNumber(),
                    line.getSkuId(),
                    null,
                    requested,
                    "ARTICLE_NOT_IDENTIFIABLE",
                    null,
                    null);
        }

        if (requested == 0) {
            // Nothing outstanding, so the vendor was never asked. Reporting this as unanswered
            // would suggest it declined to say; there was nothing to ask about.
            return new ProcurementAvailabilityResponse.Line(
                    line.getLineId(), line.getLineNumber(), line.getSkuId(), ean, 0, "NOTHING_OUTSTANDING", null, null);
        }

        SupplierStockClient.AnswerLine answer = answers.get(ean);
        if (answer == null) {
            return new ProcurementAvailabilityResponse.Line(
                    line.getLineId(),
                    line.getLineNumber(),
                    line.getSkuId(),
                    ean,
                    requested,
                    SupplierStockClient.LineStatus.NOT_ANSWERED.name(),
                    null,
                    null);
        }

        return new ProcurementAvailabilityResponse.Line(
                line.getLineId(),
                line.getLineNumber(),
                line.getSkuId(),
                ean,
                requested,
                answer.status().name(),
                // Carried through, never defaulted: null stays null so the caller can tell "said
                // nothing" from the zero that means "has none".
                answer.availableQuantity(),
                answer.earliestDeliveryDate());
    }

    /**
     * What this line still needs.
     *
     * <p>Outstanding rather than ordered: a buyer topping up a part-delivered order wants to know
     * whether the vendor can cover the remainder, not the amount already on a shelf.
     */
    private static int requestedQuantity(PurchaseOrderLineEntity line) {
        BigDecimal quantity =
                line.getOpenQuantityDecimal() == null ? line.getQuantityDecimal() : line.getOpenQuantityDecimal();
        if (quantity == null || quantity.signum() <= 0) {
            return 0;
        }
        // Rounded up: asking about 2 when 2.5 are needed would have the buyer told a quantity that
        // covers the question they asked but not the one they meant.
        return quantity.setScale(0, java.math.RoundingMode.CEILING).intValue();
    }

    private @Nullable String eanFor(PurchaseOrderLineEntity line) {
        if (line.getSkuId() == null) {
            return null;
        }
        return extProductCodeRepository
                .findById(line.getSkuId())
                .filter(code -> EAN.equalsIgnoreCase(code.getCodeType()))
                .map(ExtProductCode::getCode)
                .filter(code -> !code.isBlank())
                .orElse(null);
    }
}
