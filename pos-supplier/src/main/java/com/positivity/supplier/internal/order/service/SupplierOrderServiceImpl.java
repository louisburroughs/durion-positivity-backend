package com.positivity.supplier.internal.order.service;

import com.positivity.supplier.internal.entity.SupplierTransmissionIntentEntity;
import com.positivity.supplier.internal.enums.TransmissionAttemptState;
import com.positivity.supplier.internal.exception.SupplierConflictException;
import com.positivity.supplier.internal.exception.SupplierNotFoundException;
import com.positivity.supplier.internal.exception.SupplierValidationException;
import com.positivity.supplier.internal.repository.SupplierTransmissionIntentRepository;
import com.positivity.supplier.service.SupplierOrderService;
import com.positivity.supplier.service.model.OrderTransmissionStatus;
import com.positivity.supplier.service.model.TransmissionResolutionRequest;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transmission ledger's read and resolution surface (ADR-0026, ADR-0052 §4).
 *
 * <h2>Resolution is only ever available from {@code MANUAL_REVIEW}</h2>
 *
 * Every other state is either a definite outcome or work still in progress, and letting an
 * operator overwrite those would let a human contradict a vendor's own answer — or, worse,
 * "confirm" an order that the system is about to send, producing the duplicate this whole
 * mechanism exists to prevent. A resolution attempt from any other state is a conflict, not a
 * silently ignored no-op, so the caller learns their view was stale.
 */
@Service
@RequiredArgsConstructor
public class SupplierOrderServiceImpl implements SupplierOrderService {

    /** Recorded as the actor when a resolution somehow arrives without an authenticated caller. */
    private static final String UNKNOWN_ACTOR = "unknown";

    private final SupplierTransmissionIntentRepository intentRepository;
    private final TransmissionStateWriter stateWriter;

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<OrderTransmissionStatus> findTransmissionsByPurchaseOrderId(@NonNull UUID purchaseOrderId) {
        return intentRepository.findByPurchaseOrderIdOrderByTransmissionIntentIdDesc(purchaseOrderId).stream()
                .map(SupplierOrderServiceImpl::toView)
                .toList();
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public OrderTransmissionStatus getTransmission(@NonNull UUID transmissionIntentId) {
        return toView(require(transmissionIntentId));
    }

    @Override
    @NonNull
    @Transactional
    public OrderTransmissionStatus resolveTransmission(
            @NonNull UUID transmissionIntentId, @NonNull TransmissionResolutionRequest request) {
        SupplierTransmissionIntentEntity intent = require(transmissionIntentId);
        if (intent.getAttemptState() != TransmissionAttemptState.MANUAL_REVIEW) {
            throw new SupplierConflictException(
                    SupplierConflictException.BINDING_CAPABILITY_CONFLICT,
                    "Transmission " + transmissionIntentId + " is in state " + intent.getAttemptState()
                            + "; only a transmission awaiting MANUAL_REVIEW can be resolved by hand (ADR-0052 §4)");
        }

        TransmissionAttemptState resolvedState =
                switch (request.action()) {
                    case CONFIRM_WITH_VENDOR_REFERENCE -> {
                        if (request.supplierOrderNumber() == null
                                || request.supplierOrderNumber().isBlank()) {
                            // The vendor's own reference is the evidence. Without it the operator is
                            // asserting a confirmation with nothing to check it against later.
                            throw new SupplierValidationException(
                                    SupplierValidationException.AUTH_REFS_INCOMPLETE,
                                    "supplierOrderNumber is required when confirming a transmission with a vendor"
                                            + " reference");
                        }
                        yield TransmissionAttemptState.CONFIRMED;
                    }
                    case MARK_NOT_RECEIVED -> TransmissionAttemptState.FAILED;
                    case CANCEL -> TransmissionAttemptState.CANCELLED;
                };

        stateWriter.recordResolution(
                transmissionIntentId,
                resolvedState,
                request.action().name(),
                currentActor(),
                request.evidence(),
                request.supplierOrderNumber());

        return toView(require(transmissionIntentId));
    }

    private SupplierTransmissionIntentEntity require(UUID transmissionIntentId) {
        return intentRepository
                .findById(transmissionIntentId)
                .orElseThrow(() -> new SupplierNotFoundException(
                        SupplierNotFoundException.EXCHANGE_AUDIT_NOT_FOUND,
                        "No transmission exists with id " + transmissionIntentId));
    }

    /**
     * The authenticated caller, recorded verbatim on the resolution and its event.
     *
     * <p>Read from the security context rather than taken as a parameter: an actor a caller can
     * supply is an actor a caller can choose, and this one ends up in an audit trail that
     * settles disputes about who told the system an order was placed.
     */
    private static String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            return UNKNOWN_ACTOR;
        }
        String name = authentication.getName().trim();
        return name.isEmpty() ? UNKNOWN_ACTOR : name;
    }

    private static OrderTransmissionStatus toView(SupplierTransmissionIntentEntity intent) {
        return new OrderTransmissionStatus(
                intent.getTransmissionIntentId(),
                intent.getPurchaseOrderId(),
                intent.getPurchaseOrderNumber(),
                intent.getSupplierRef(),
                intent.getDocumentId(),
                toViewState(intent.getAttemptState()),
                intent.getSupplierOrderNumber(),
                intent.getVendorErrorCode(),
                intent.getVendorReason(),
                intent.getDispatchAttempts(),
                intent.getLatestScheduledDeliveryDate(),
                intent.getLastStatusAt(),
                intent.getUpdatedAt(),
                intent.getResolutionAction(),
                intent.getResolvedBy(),
                intent.getResolvedAt(),
                intent.getLastError());
    }

    private static OrderTransmissionStatus.State toViewState(TransmissionAttemptState state) {
        return switch (state) {
            case PENDING -> OrderTransmissionStatus.State.PENDING;
            case DISPATCHING -> OrderTransmissionStatus.State.DISPATCHING;
            case SENT_AWAITING_RESULT -> OrderTransmissionStatus.State.SENT_AWAITING_RESULT;
            case CONFIRMED -> OrderTransmissionStatus.State.CONFIRMED;
            case REJECTED -> OrderTransmissionStatus.State.REJECTED;
            case MANUAL_REVIEW -> OrderTransmissionStatus.State.MANUAL_REVIEW;
            case FAILED -> OrderTransmissionStatus.State.FAILED;
            case CANCELLED -> OrderTransmissionStatus.State.CANCELLED;
        };
    }
}
