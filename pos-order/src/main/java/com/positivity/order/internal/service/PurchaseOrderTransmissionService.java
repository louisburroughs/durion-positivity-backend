package com.positivity.order.internal.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.supplier.SupplierOrderRequestedLine;
import com.positivity.domainevents.supplier.SupplierOrderRequestedV1;
import com.positivity.order.internal.config.OutboxEventWriter;
import com.positivity.order.internal.entity.ExtProductCode;
import com.positivity.order.internal.entity.ExtSupplierArticleCode;
import com.positivity.order.internal.entity.PurchaseOrderEntity;
import com.positivity.order.internal.entity.PurchaseOrderLineEntity;
import com.positivity.order.internal.enums.PurchaseOrderStatus;
import com.positivity.order.internal.enums.TransmissionState;
import com.positivity.order.internal.exception.PurchaseOrderNotFoundException;
import com.positivity.order.internal.exception.PurchaseOrderNotTransmittableException;
import com.positivity.order.internal.repository.ExtProductCodeRepository;
import com.positivity.order.internal.repository.ExtSupplierArticleCodeRepository;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends an approved purchase order to its vendor (CAP-320 #1330, ADR-0052).
 *
 * <h2>Why this domain refuses rather than relying on the far side</h2>
 *
 * pos-supplier will never re-send an order and offers no operation to make it, deliberately: a
 * blind re-send is how one purchase order becomes two deliveries. The consequence lands here.
 * Re-ordering is this domain's job, and so is declining to order twice — pos-supplier's
 * active-intent guard is a backstop, not the design. Every refusal below is a refusal to guess,
 * because the cost of guessing is not an error message but a lorry.
 *
 * <h2>intentType is a statement only this domain can make</h2>
 *
 * Nothing in the payload lets pos-supplier tell a revision from a redelivery. {@code INITIAL},
 * {@code REVISION} and {@code REPLACEMENT} are distinguished by why the order is being sent, which
 * is knowledge the order itself holds: whether it has been sent before, and whether it has changed
 * since. Defaulting it would be asserting something untrue about a delivery.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseOrderTransmissionService {

    private static final String SOURCE = "pos-order";

    /** The code type the vendor understands as an article identifier. */
    private static final String EAN = "EAN";

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final ExtProductCodeRepository extProductCodeRepository;
    private final ExtSupplierArticleCodeRepository extSupplierArticleCodeRepository;
    /**
     * Optional because the outbox is not wired in every deployment — but unlike a fact publisher,
     * absence here is fatal rather than inert. Silently publishing nothing would leave the order
     * marked as sent and a buyer waiting on goods nobody ever ordered.
     */
    private final org.springframework.beans.factory.ObjectProvider<OutboxEventWriter> outboxEventWriter;

    private final Clock clock;

    /**
     * Asks pos-supplier to transmit the order, publishing in the transaction that records the
     * request.
     *
     * <p>Outbox rather than a direct publish, so a command can never describe an order that rolled
     * back: {@code supplier.commands.v1} has one consumer group keyed on event id alone, and a
     * duplicate publication is not harmless.
     */
    @Transactional
    public void requestTransmission(@NonNull UUID purchaseOrderId, @NonNull String actorId) {
        PurchaseOrderEntity order = purchaseOrderRepository
                .findById(purchaseOrderId)
                .orElseThrow(() -> new PurchaseOrderNotFoundException(purchaseOrderId));

        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            throw PurchaseOrderNotTransmittableException.publishingUnavailable();
        }

        guardTransmittable(order);
        List<SupplierOrderRequestedLine> lines = resolveLines(order);
        SupplierOrderRequestedV1.IntentType intentType = intentTypeFor(order);

        Instant now = Instant.now(clock);

        order.setTransmissionState(TransmissionState.REQUESTED);
        // The intent id is deliberately not set here. pos-supplier mints it on consuming the
        // command, which is what makes a redelivered command a no-op rather than a second physical
        // order (ADR-0052 §1). It arrives back on the result events, and is recorded then.
        order.setTransmissionRequestedAt(now);
        order.setTransmissionCount(order.getTransmissionCount() == null ? 1 : order.getTransmissionCount() + 1);
        order.setTransmittedVersionNumber(order.getVersionNumber());
        // Cleared, not kept: a previous refusal explains the order's last state, not this one, and
        // a stale reason beside a fresh request reads as though the vendor has refused it again.
        order.setTransmissionRejectionReason(null);
        order.setTransmissionVendorReason(null);
        purchaseOrderRepository.save(order);

        writer.publish(
                DomainTopics.commands("supplier"),
                new DomainEventEnvelope<>(
                        UUIDv7Generator.generate(),
                        SupplierOrderRequestedV1.EVENT_TYPE,
                        SupplierOrderRequestedV1.SCHEMA_VERSION,
                        order.getPurchaseOrderId(),
                        order.getVersionNumber() == null
                                ? 0L
                                : order.getVersionNumber().longValue(),
                        now,
                        SOURCE,
                        null,
                        actorId,
                        new SupplierOrderRequestedV1(
                                order.getPurchaseOrderId(),
                                // The order's own revision. Together with intentType it is what
                                // pos-supplier enforces intent uniqueness on, so re-sending this
                                // exact command creates no second intent while a genuine revision
                                // does — by construction rather than by our care.
                                revisionOf(order),
                                intentType,
                                order.getSupplierRef(),
                                order.getShipToLocationId(),
                                order.getPoNumber(),
                                lines)));

        log.info(
                "Requested transmission of purchase order {} to {} as {} (attempt {})",
                order.getPurchaseOrderId(),
                order.getSupplierRef(),
                intentType,
                order.getTransmissionCount());
    }

    private static void guardTransmittable(PurchaseOrderEntity order) {
        if (order.getSupplierRef() == null || order.getSupplierRef().isBlank()) {
            throw PurchaseOrderNotTransmittableException.noSupplierRef();
        }
        // A draft is not a commitment. Sending one would put an order in front of a vendor that
        // nobody inside has yet agreed to place.
        if (order.getStatus() != PurchaseOrderStatus.APPROVED
                && order.getStatus() != PurchaseOrderStatus.PARTIALLY_RECEIVED) {
            throw PurchaseOrderNotTransmittableException.notApproved(
                    order.getStatus() == null
                            ? "an unknown state"
                            : order.getStatus().name());
        }
        switch (order.getTransmissionState()) {
            case REQUESTED -> throw PurchaseOrderNotTransmittableException.alreadyInFlight();
            case MANUAL_REVIEW -> throw PurchaseOrderNotTransmittableException.awaitingManualReview();
            default -> {
                // NOT_TRANSMITTED, CONFIRMED and REJECTED may all be sent: the first is a first
                // send, and the other two are a revision and a re-order respectively.
            }
        }
    }

    /**
     * The order's lines as articles the vendor can recognise.
     *
     * <p>A line whose article cannot be identified refuses the whole transmission. Withholding it
     * instead would send an order that silently differs from the one on screen, and the difference
     * would surface as a short delivery weeks later rather than as a question now.
     *
     * <p>Both an EAN and the vendor's own article code may travel on the same line (CAP-320 #1347);
     * neither is invented when absent. A vendor that carries no EAN is unidentifiable until its own
     * article code replicates, not before — sending a fabricated EAN would name the wrong article.
     */
    private List<SupplierOrderRequestedLine> resolveLines(PurchaseOrderEntity order) {
        List<SupplierOrderRequestedLine> resolved = new ArrayList<>();
        List<Integer> unidentifiable = new ArrayList<>();
        List<Integer> fractional = new ArrayList<>();

        for (PurchaseOrderLineEntity line : order.getLines().stream()
                .sorted(Comparator.comparing(
                        PurchaseOrderLineEntity::getLineNumber, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList()) {
            String ean = eanFor(line);
            String supplierArticleCode = supplierArticleCodeFor(order.getSupplierRef(), line);
            if (ean == null && supplierArticleCode == null) {
                unidentifiable.add(line.getLineNumber());
                continue;
            }
            // What is still owed, not what was originally ordered: a revision sent after a part
            // delivery must ask for the remainder, or the vendor ships the whole order again.
            BigDecimal quantity =
                    line.getOpenQuantityDecimal() == null ? line.getQuantityDecimal() : line.getOpenQuantityDecimal();
            Integer whole = wholeUnits(quantity);
            if (whole == null) {
                fractional.add(line.getLineNumber());
                continue;
            }
            resolved.add(new SupplierOrderRequestedLine(
                    line.getLineNumber() == null ? 0 : line.getLineNumber(),
                    ean,
                    supplierArticleCode,
                    whole,
                    order.getExpectedDeliveryDate()));
        }

        if (!unidentifiable.isEmpty()) {
            throw PurchaseOrderNotTransmittableException.articlesNotIdentifiable(unidentifiable);
        }
        if (!fractional.isEmpty()) {
            throw PurchaseOrderNotTransmittableException.fractionalQuantity(fractional);
        }
        return resolved;
    }

    /** The quantity as whole units, or null when it cannot be stated as one. */
    private static Integer wholeUnits(BigDecimal quantity) {
        if (quantity == null) {
            return null;
        }
        try {
            return quantity.intValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /** The revision this command transmits; the order's version, floored at zero. */
    private static int revisionOf(PurchaseOrderEntity order) {
        return order.getVersionNumber() == null ? 0 : Math.max(0, order.getVersionNumber());
    }

    private String eanFor(PurchaseOrderLineEntity line) {
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

    /** The vendor's own article code for this line's product, when this replica holds one (CAP-320 #1347). */
    private String supplierArticleCodeFor(String supplierRef, PurchaseOrderLineEntity line) {
        if (line.getSkuId() == null || supplierRef == null || supplierRef.isBlank()) {
            return null;
        }
        return extSupplierArticleCodeRepository
                .findBySupplierRefAndProductId(supplierRef, line.getSkuId())
                .map(ExtSupplierArticleCode::getSupplierArticleCode)
                .filter(code -> !code.isBlank())
                .orElse(null);
    }

    /**
     * Why this order is being sent, stated from the order's own history.
     *
     * <ul>
     *   <li>Never sent → {@code INITIAL}.
     *   <li>Sent, and changed since → {@code REVISION}: the vendor holds an older version of an
     *       order it has acknowledged, and this corrects it.
     *   <li>Sent, refused, unchanged → {@code REPLACEMENT}: the previous send terminated without
     *       the vendor taking the order, so this is a fresh one rather than a correction.
     * </ul>
     *
     * <p>{@code SPLIT} is not derived. Splitting an order across vendors or deliveries is a
     * decision nobody has made here, and inferring it from a partial send would claim the buyer
     * intended something they did not.
     */
    private static SupplierOrderRequestedV1.IntentType intentTypeFor(PurchaseOrderEntity order) {
        if (order.getTransmissionCount() == null || order.getTransmissionCount() == 0) {
            return SupplierOrderRequestedV1.IntentType.INITIAL;
        }
        boolean changedSinceLastSend = order.getTransmittedVersionNumber() == null
                || order.getVersionNumber() == null
                || order.getVersionNumber() > order.getTransmittedVersionNumber();
        if (changedSinceLastSend) {
            return SupplierOrderRequestedV1.IntentType.REVISION;
        }
        return SupplierOrderRequestedV1.IntentType.REPLACEMENT;
    }
}
