package com.positivity.order.internal.service;

import com.positivity.order.internal.entity.ApprovalRecord;
import com.positivity.order.internal.entity.OverrideStatus;
import com.positivity.order.internal.entity.PriceOverride;
import com.positivity.order.internal.entity.PriceOverrideReasonCode;
import com.positivity.order.internal.entity.SalesOrderLine;
import com.positivity.order.internal.event.OrderPriceOverrideApplied;
import com.positivity.order.internal.exception.InvalidPriceOverrideException;
import com.positivity.order.internal.exception.PriceOverrideNotFoundException;
import com.positivity.order.internal.repository.ApprovalRecordRepository;
import com.positivity.order.internal.repository.PriceOverrideRepository;
import com.positivity.order.internal.repository.SalesOrderLineRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import com.positivity.order.service.PriceOverrideService;
import com.positivity.order.service.model.ApplyPriceOverrideRequest;
import com.positivity.order.service.model.ApproveOverrideCommand;
import com.positivity.order.service.model.PriceOverrideDetail;
import com.positivity.order.service.model.PriceOverrideResult;
import com.positivity.order.service.model.RejectOverrideCommand;
import com.positivity.security.common.SecurityContextHelper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of PriceOverrideService.
 * 
 * Handles price override business logic including:
 * - Applying overrides with permission checks
 * - Manager approval workflow
 * - Audit trail creation
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PriceOverrideServiceImpl implements PriceOverrideService {
    private final Clock clock;

                private final PriceOverrideRepository priceOverrideRepository;
                private final ApprovalRecordRepository approvalRecordRepository;
                private final SalesOrderRepository salesOrderRepository;
                private final SalesOrderLineRepository salesOrderLineRepository;
                private final ApplicationEventPublisher eventPublisher;

        // Business rule: Discount percentage threshold requiring approval
        private static final BigDecimal APPROVAL_THRESHOLD_PERCENTAGE = BigDecimal.valueOf(10.0);
        // Business rule: Discount amount threshold requiring approval
        private static final BigDecimal APPROVAL_THRESHOLD_AMOUNT = BigDecimal.valueOf(50.0);

        @Override
        @Transactional
        public PriceOverrideResult applyPriceOverride(ApplyPriceOverrideRequest request) {
                String actorUsername = SecurityContextHelper.getCurrentUsernameOrDefault("system");
                UUID actorUuid = SecurityContextHelper.getCurrentUserIdAsUuidOrDefault(
                                UUID.fromString("00000000-0000-0000-0000-000000000000"));

                // Idempotency check
                if (request.getIdempotencyKey() != null) {
                        Optional<PriceOverride> existing = priceOverrideRepository
                                        .findByIdempotencyKey(request.getIdempotencyKey());
                        if (existing.isPresent()) {
                                log.info("Idempotent replay for key={}, returning existing overrideId={}",
                                                request.getIdempotencyKey(), existing.get().getOverrideId());
                                return toResponse(existing.get());
                        }
                }

                log.info("Applying price override for order {} line {} by user {}",
                                request.getOrderId(), request.getOrderLineId(), actorUsername);

                // Validate business rules
                validateOverrideRequest(request);

                // Calculate discount details
                BigDecimal discountAmount = request.getOriginalPrice().subtract(request.getOverridePrice());
                BigDecimal discountPercentage = calculateDiscountPercentage(
                                request.getOriginalPrice(), request.getOverridePrice());

                // Determine if approval is required
                boolean requiresApproval = requiresApproval(discountAmount, discountPercentage);
                OverrideStatus initialStatus = requiresApproval ? OverrideStatus.PENDING_APPROVAL
                                : OverrideStatus.APPROVED;

                // Create price override record
                PriceOverride override = PriceOverride.builder()
                                .orderId(UUID.fromString(request.getOrderId()))
                                .orderLineId(UUID.fromString(request.getOrderLineId()))
                                .productId(UUID.fromString(request.getProductId()))
                                .originalPrice(request.getOriginalPrice())
                                .overridePrice(request.getOverridePrice())
                                .reasonCode(PriceOverrideReasonCode.valueOf(request.getReasonCode()))
                                .justification(request.getJustification())
                                .status(initialStatus)
                                .requiresApproval(requiresApproval)
                                .affectsCommission(!requiresApproval)
                                .idempotencyKey(request.getIdempotencyKey())
                                .requestedByUserId(actorUuid)
                                .createdBy(actorUsername)
                                .updatedBy(actorUsername)
                                .build();

                // Auto-approve if no approval required
                if (!requiresApproval) {
                        override.setApprovedAt(Instant.now(clock));
                        // APPROVED is the terminal state for the auto-approve path (equivalent to
                        // "Applied")
                        override.setApprovedByUserId(actorUuid);
                }

                PriceOverride savedOverride = priceOverrideRepository.save(override);

                if (!requiresApproval) {
                        // Update the order line's unit price to reflect the approved override
                        salesOrderLineRepository.findById(savedOverride.getOrderLineId()).ifPresent(line -> {
                                line.setUnitPrice(savedOverride.getOverridePrice());
                                salesOrderLineRepository.save(line);

                                // Recalculate order subtotal
                                salesOrderRepository.findById(savedOverride.getOrderId()).ifPresent(order -> {
                                        List<SalesOrderLine> lines = salesOrderLineRepository
                                                        .findByOrder_OrderId(order.getOrderId());
                                        BigDecimal newSubtotal = lines.stream()
                                                        .map(l -> l.getUnitPrice().multiply(
                                                                        BigDecimal.valueOf(l.getQuantity())))
                                                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                                                        .setScale(4, RoundingMode.HALF_UP);
                                        order.setSubtotal(newSubtotal);
                                        order.setUpdatedBy(actorUsername);
                                        salesOrderRepository.save(order);
                                });
                        });

                        // Publish domain event
                        eventPublisher.publishEvent(new OrderPriceOverrideApplied(
                                        savedOverride.getOverrideId(),
                                        savedOverride.getOrderId(),
                                        savedOverride.getOrderLineId(),
                                        savedOverride.getProductId(),
                                        savedOverride.getOriginalPrice(),
                                        savedOverride.getOverridePrice(),
                                        actorUsername));

                        // Commission broker event emission is pending async integration.
                        log.warn("COMMISSION_EVENT_TODO: override {} affects commission; broker integration pending",
                                        savedOverride.getOverrideId());
                }

                log.info("Price override created with ID {} - Status: {}, Requires Approval: {}",
                                savedOverride.getOverrideId(), savedOverride.getStatus(), requiresApproval);

                return toResponse(savedOverride);
        }

        @Override
        @Transactional
        public PriceOverrideDetail approveOverride(UUID overrideId, ApproveOverrideCommand command) {
                UUID approverUserId = resolveActorUuid();
                log.info("Approving price override {} by user {} with role {}",
                                overrideId, approverUserId, command.approverRole());

                PriceOverride override = findOverrideById(overrideId);

                if (override.getStatus() != OverrideStatus.PENDING_APPROVAL) {
                        throw new InvalidPriceOverrideException(
                                        "Cannot approve override in status: " + override.getStatus());
                }

                override.setStatus(OverrideStatus.APPROVED);
                override.setApprovedByUserId(approverUserId);
                override.setApprovedAt(Instant.now(clock));

                override = priceOverrideRepository.save(override);

                ApprovalRecord approvalRecord = ApprovalRecord.builder()
                                .priceOverrideId(overrideId)
                                .reviewerUserId(approverUserId)
                                .reviewerRole(command.approverRole())
                                .action("APPROVED")
                                .comments(command.comments())
                                .build();

                approvalRecordRepository.save(approvalRecord);

                log.info("Price override {} approved by {} ({})",
                                overrideId, approverUserId, command.approverRole());

                return toDetail(override);
        }

        @Override
        @Transactional
        public PriceOverrideDetail rejectOverride(UUID overrideId, RejectOverrideCommand command) {
                UUID reviewerUserId = resolveActorUuid();
                log.info("Rejecting price override {} by user {} with role {}",
                                overrideId, reviewerUserId, command.reviewerRole());

                PriceOverride override = findOverrideById(overrideId);

                if (override.getStatus() != OverrideStatus.PENDING_APPROVAL) {
                        throw new InvalidPriceOverrideException(
                                        "Cannot reject override in status: " + override.getStatus());
                }

                override.setStatus(OverrideStatus.REJECTED);
                override.setRejectedByUserId(reviewerUserId);
                override.setRejectionReason(command.reason());
                override.setRejectedAt(Instant.now(clock));

                override = priceOverrideRepository.save(override);

                ApprovalRecord approvalRecord = ApprovalRecord.builder()
                                .priceOverrideId(overrideId)
                                .reviewerUserId(reviewerUserId)
                                .reviewerRole(command.reviewerRole())
                                .action("REJECTED")
                                .comments(command.comments())
                                .build();

                approvalRecordRepository.save(approvalRecord);

                log.info("Price override {} rejected by {} ({}): {}",
                                overrideId, reviewerUserId, command.reviewerRole(), command.reason());

                return toDetail(override);
        }

        @Override
        @Transactional(readOnly = true)
        public PriceOverrideDetail getOverrideById(UUID overrideId) {
                return toDetail(findOverrideById(overrideId));
        }

        @Override
        @Transactional(readOnly = true)
        public List<PriceOverrideDetail> getOverridesByOrderId(UUID orderId) {
                return priceOverrideRepository.findByOrderId(orderId).stream().map(this::toDetail).toList();
        }

        @Override
        @Transactional(readOnly = true)
        public List<PriceOverrideDetail> getPendingApprovals() {
                return priceOverrideRepository.findByStatusAndRequiresApproval(
                                OverrideStatus.PENDING_APPROVAL, true).stream().map(this::toDetail).toList();
        }

        @Override
        @Transactional(readOnly = true)
        public List<PriceOverrideDetail> getOverridesByDateRange(Instant startDate, Instant endDate) {
                return priceOverrideRepository.findByCreatedAtBetween(startDate, endDate).stream().map(this::toDetail)
                                .toList();
        }

        @Override
        @Transactional(readOnly = true)
        public List<PriceOverrideDetail> getOverridesByStatus(String status) {
                OverrideStatus parsedStatus;
                try {
                        parsedStatus = OverrideStatus.valueOf(status.toUpperCase());
                } catch (IllegalArgumentException e) {
                        throw new InvalidPriceOverrideException("Invalid override status: " + status);
                }
                return priceOverrideRepository.findByStatus(parsedStatus).stream().map(this::toDetail).toList();
        }

        private PriceOverride findOverrideById(UUID overrideId) {
                return priceOverrideRepository.findById(overrideId)
                                .orElseThrow(() -> new PriceOverrideNotFoundException(overrideId));
        }

        private PriceOverrideResult toResponse(PriceOverride override) {
                return toPriceOverrideResult(override);
        }

        private PriceOverrideResult toPriceOverrideResult(PriceOverride override) {
                BigDecimal discount = override.getOriginalPrice().subtract(override.getOverridePrice());
                BigDecimal pct = override.getOriginalPrice().compareTo(BigDecimal.ZERO) != 0
                                ? discount.divide(override.getOriginalPrice(), 4, java.math.RoundingMode.HALF_UP)
                                                .multiply(BigDecimal.valueOf(100))
                                : BigDecimal.ZERO;
                return new PriceOverrideResult(
                                override.getOverrideId(),
                                override.getOrderId().toString(),
                                override.getOrderLineId().toString(),
                                override.getProductId().toString(),
                                override.getOriginalPrice(),
                                override.getOverridePrice(),
                                discount,
                                pct,
                                override.getReasonCode() != null ? override.getReasonCode().name() : null,
                                override.getJustification(),
                                override.getStatus().name(),
                                override.getRequiresApproval(),
                                override.getAffectsCommission(),
                                nullSafeToString(override.getRequestedByUserId()),
                                override.getCreatedAt(),
                                override.getStatus() == com.positivity.order.internal.entity.OverrideStatus.APPROVED
                                                ? "Approved and applied immediately"
                                                : "Pending manager approval");
        }

        private PriceOverrideDetail toDetail(PriceOverride override) {
                BigDecimal discount = override.getOriginalPrice().subtract(override.getOverridePrice());
                BigDecimal pct = override.getOriginalPrice().compareTo(BigDecimal.ZERO) != 0
                                ? discount.divide(override.getOriginalPrice(), 4, java.math.RoundingMode.HALF_UP)
                                                .multiply(BigDecimal.valueOf(100))
                                : BigDecimal.ZERO;
                return new PriceOverrideDetail(
                                override.getOverrideId(),
                                override.getOrderId().toString(),
                                override.getOrderLineId().toString(),
                                override.getProductId().toString(),
                                override.getOriginalPrice(),
                                override.getOverridePrice(),
                                discount,
                                pct,
                                override.getReasonCode() != null ? override.getReasonCode().name() : null,
                                override.getJustification(),
                                override.getStatus().name(),
                                override.getRequiresApproval(),
                                override.getAffectsCommission(),
                                nullSafeToString(override.getRequestedByUserId()),
                                nullSafeToString(override.getApprovedByUserId()),
                                nullSafeToString(override.getRejectedByUserId()),
                                override.getRejectionReason(),
                                override.getCreatedAt(),
                                override.getUpdatedAt(),
                                override.getApprovedAt(),
                                override.getRejectedAt(),
                                override.getAppliedAt());
        }

        private static String nullSafeToString(UUID uuid) {
                return uuid == null ? null : uuid.toString();
        }

        private UUID resolveActorUuid() {
                String name = SecurityContextHelper
                                .getCurrentUsernameOrDefault("00000000-0000-0000-0000-000000000000");
                try {
                        return UUID.fromString(name);
                } catch (IllegalArgumentException e) {
                        return new UUID(0L, 0L);
                }
        }

        /**
         * Validates the override request against business rules.
         */
        private void validateOverrideRequest(ApplyPriceOverrideRequest request) {
                if (request.getOverridePrice().compareTo(request.getOriginalPrice()) > 0) {
                        throw new InvalidPriceOverrideException(
                                        "Override price cannot be greater than original price");
                }

                if (request.getOverridePrice().compareTo(BigDecimal.ZERO) < 0) {
                        throw new InvalidPriceOverrideException(
                                        "Override price cannot be negative");
                }
        }

        /**
         * Determines if manager approval is required based on business rules.
         */
        private boolean requiresApproval(BigDecimal discountAmount, BigDecimal discountPercentage) {
                return discountAmount.compareTo(APPROVAL_THRESHOLD_AMOUNT) >= 0 ||
                                discountPercentage.compareTo(APPROVAL_THRESHOLD_PERCENTAGE) > 0;
        }

        /**
         * Calculates discount percentage.
         */
        private BigDecimal calculateDiscountPercentage(BigDecimal originalPrice, BigDecimal overridePrice) {
                if (originalPrice.compareTo(BigDecimal.ZERO) == 0) {
                        return BigDecimal.ZERO;
                }
                return originalPrice.subtract(overridePrice)
                                .divide(originalPrice, 4, java.math.RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100));
        }
}
