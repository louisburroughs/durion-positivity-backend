package com.positivity.supplier.internal.order.service;

import com.positivity.domainevents.supplier.SupplierOrderRequestedLine;
import com.positivity.domainevents.supplier.SupplierOrderRequestedV1;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.supplier.internal.domain.model.SupplierPurchaseOrder;
import com.positivity.supplier.internal.entity.SupplierAccountEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.entity.SupplierTransmissionIntentEntity;
import com.positivity.supplier.internal.entity.SupplierTransmissionLineEntity;
import com.positivity.supplier.internal.enums.SupplierAccountRole;
import com.positivity.supplier.internal.enums.TransmissionAttemptState;
import com.positivity.supplier.internal.repository.SupplierAccountRepository;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import com.positivity.supplier.internal.repository.SupplierTransmissionIntentRepository;
import com.positivity.supplier.internal.repository.SupplierTransmissionLineRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mints transmission intents from {@code supplier.order.requested} commands (ADR-0052 §1).
 *
 * <h2>Exactly-once-intent, in one transaction</h2>
 *
 * {@code MANDATORY} propagation: the intent, its lines and the consumer's {@code processed_events}
 * row commit together or not at all. Without that, a crash between them could leave an order
 * recorded as consumed but never queued (silently unordered goods) or queued twice from one
 * command.
 *
 * <h2>The duplicate check is the duplicate-order guard</h2>
 *
 * Idempotency by {@code eventId} handles a redelivered message. It does <em>not</em> handle a
 * re-issued command — the same order requested again with a fresh event id, which is what a
 * retrying producer, a replayed topic or an impatient operator produces. That is caught here, by
 * the active-intent key: if the tuple is already claimed, this command is a repeat of an order
 * that already exists, and minting a second intent would give it a second document id and a
 * second delivery.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransmissionIntentWriter {

    private final SupplierProfileRepository profileRepository;
    private final SupplierAccountRepository accountRepository;
    private final SupplierTransmissionIntentRepository intentRepository;
    private final SupplierTransmissionLineRepository lineRepository;

    /**
     * Records one requested order as a dispatchable intent.
     *
     * @param command the requested order
     * @param correlationId correlation to stamp on the transmission and its events
     * @return the new intent, or empty when the command is a repeat of an order that already has
     *     an active intent
     * @throws UnknownSupplierException when the command names a vendor alias this deployment does
     *     not have
     */
    @NonNull
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<SupplierTransmissionIntentEntity> mint(
            @NonNull SupplierOrderRequestedV1 command, @NonNull String correlationId) {

        SupplierProfileEntity profile = profileRepository
                .findBySupplierRef(command.supplierRef())
                .filter(SupplierProfileEntity::isEnabled)
                .orElseThrow(() -> new UnknownSupplierException(command.supplierRef()));

        String activeKey = SupplierTransmissionIntentEntity.activeKeyFor(
                profile.getVendorProfileId(),
                toIntentType(command.intentType()),
                command.purchaseOrderId(),
                command.revision());

        Optional<SupplierTransmissionIntentEntity> existing = intentRepository.findByActiveIntentKey(activeKey);
        if (existing.isPresent()) {
            log.info(
                    "Ignoring repeat order request for purchase order {} revision {} at {}: intent {} already holds it"
                            + " (state {})",
                    command.purchaseOrderId(),
                    command.revision(),
                    command.supplierRef(),
                    existing.get().getTransmissionIntentId(),
                    existing.get().getAttemptState());
            return Optional.empty();
        }

        // The id is minted here rather than assigned by the database, because the document id
        // derives from it and the lines are written against it in this same transaction.
        UUID intentId = UUIDv7Generator.generate();
        String billingAccountNumber =
                accountRepository
                        .findByVendorProfileIdAndRole(profile.getVendorProfileId(), SupplierAccountRole.BILLING)
                        .stream()
                        .findFirst()
                        .map(SupplierAccountEntity::getAccountNumber)
                        .orElse(null);

        SupplierTransmissionIntentEntity intent = intentRepository.save(SupplierTransmissionIntentEntity.builder()
                .transmissionIntentId(intentId)
                .vendorProfileId(profile.getVendorProfileId())
                .supplierRef(profile.getSupplierRef())
                .purchaseOrderId(command.purchaseOrderId())
                .purchaseOrderNumber(truncate(command.purchaseOrderNumber(), 64))
                .intentType(toIntentType(command.intentType()))
                .revision(command.revision())
                .documentId(TransmissionDocumentId.forIntent(intentId))
                .activeIntentKey(activeKey)
                .attemptState(TransmissionAttemptState.PENDING)
                .deliveryLocationId(command.deliveryLocationId())
                .billingAccountNumber(billingAccountNumber)
                .correlationId(correlationId)
                .build());

        for (SupplierOrderRequestedLine line : command.lines()) {
            lineRepository.save(SupplierTransmissionLineEntity.builder()
                    .transmissionIntentId(intentId)
                    .lineNumber(line.lineNumber())
                    .articleEan(truncate(line.articleEan(), 64))
                    .supplierArticleCode(truncate(line.supplierArticleCode(), 64))
                    .quantity(line.quantity())
                    .requestedDeliveryDate(line.requestedDeliveryDate())
                    .build());
        }

        log.info(
                "Minted transmission intent {} (document {}) for purchase order {} revision {} at {}",
                intentId,
                intent.getDocumentId(),
                command.purchaseOrderId(),
                command.revision(),
                profile.getSupplierRef());
        return Optional.of(intent);
    }

    private static SupplierPurchaseOrder.IntentType toIntentType(SupplierOrderRequestedV1.IntentType intentType) {
        return switch (intentType) {
            case INITIAL -> SupplierPurchaseOrder.IntentType.INITIAL;
            case REVISION -> SupplierPurchaseOrder.IntentType.REVISION;
            case REPLACEMENT -> SupplierPurchaseOrder.IntentType.REPLACEMENT;
            case SPLIT -> SupplierPurchaseOrder.IntentType.SPLIT;
        };
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /**
     * The command named a vendor alias this deployment does not have, or has disabled.
     *
     * <p>A producer defect rather than a transient condition: retrying will not conjure the
     * profile. The consumer records the command as processed and moves on, so one bad command
     * cannot stall every other vendor's orders behind it on the same partition.
     */
    public static class UnknownSupplierException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public UnknownSupplierException(String supplierRef) {
            super("No enabled vendor profile is configured for supplier '" + supplierRef + "'");
        }
    }
}
