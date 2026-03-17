package com.positivity.order.service;

import com.positivity.order.config.TestSecurityConfig;
import com.positivity.order.internal.entity.OverrideStatus;
import com.positivity.order.internal.entity.PriceOverride;
import com.positivity.order.internal.entity.PriceOverrideReasonCode;
import com.positivity.order.internal.entity.PriceSource;
import com.positivity.order.internal.entity.FulfillmentStatus;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderLine;
import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.event.OrderPriceOverrideApplied;
import com.positivity.order.internal.exception.InvalidPriceOverrideException;
import com.positivity.order.internal.exception.PriceOverrideIdempotencyConflictException;
import com.positivity.order.internal.exception.PriceOverrideNotFoundException;
import com.positivity.order.internal.repository.ApprovalRecordRepository;
import com.positivity.order.internal.repository.PriceOverrideRepository;
import com.positivity.order.internal.repository.SalesOrderLineRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import com.positivity.order.service.model.ApplyPriceOverrideRequest;
import com.positivity.order.service.model.ApproveOverrideCommand;
import com.positivity.order.service.model.PriceOverrideDetail;
import com.positivity.order.service.model.PriceOverrideResult;
import com.positivity.order.service.model.RejectOverrideCommand;
import com.positivity.security.common.GatewaySecurityConstants;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for PriceOverrideService.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestSecurityConfig.class)
@RecordApplicationEvents
class PriceOverrideServiceTest {

        @Autowired
        private PriceOverrideService priceOverrideService;

        @Autowired
        private PriceOverrideRepository priceOverrideRepository;

        @Autowired
        private ApprovalRecordRepository approvalRecordRepository;

        @Autowired
        private SalesOrderRepository salesOrderRepository;

        @Autowired
        private SalesOrderLineRepository salesOrderLineRepository;

        @Autowired
        private ApplicationEvents applicationEvents;

        @BeforeEach
        void setUp() {
                priceOverrideRepository.deleteAll();
                approvalRecordRepository.deleteAll();
                salesOrderLineRepository.deleteAll();
                salesOrderRepository.deleteAll();
        }

        @AfterEach
        void tearDown() {
                SecurityContextHolder.clearContext();
        }

        @Test
        void testApplyPriceOverride_SmallDiscount_AutoApproved() {
                // Given: Small discount (5%) that doesn't require approval
                createOrderWithLine(
                                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                UUID.fromString("00000000-0000-0000-0000-000000000009"),
                                BigDecimal.valueOf(100.00));

                ApplyPriceOverrideRequest request = new ApplyPriceOverrideRequest();
                request.setOrderId(UUID.fromString("00000000-0000-0000-0000-000000000001").toString());
                request.setOrderLineId(UUID.fromString("00000000-0000-0000-0000-000000000009").toString());
                request.setProductId(UUID.fromString("00000000-0000-0000-0000-000000000011").toString());
                request.setOriginalPrice(BigDecimal.valueOf(100.00));
                request.setOverridePrice(BigDecimal.valueOf(95.00));
                request.setReasonCode("CUSTOMER_LOYALTY");
                request.setJustification("Loyal customer discount");

                // When
                PriceOverrideResult response = priceOverrideService.applyPriceOverride(request);

                // Then
                assertThat(response).isNotNull();
                assertThat(response.overrideId()).isNotNull();
                assertThat(response.status()).isEqualTo(OverrideStatus.APPROVED.name());
                assertThat(response.requiresApproval()).isFalse();
                assertThat(response.discountAmount()).isEqualByComparingTo(BigDecimal.valueOf(5.00));
                assertThat(response.discountPercentage()).isEqualByComparingTo(BigDecimal.valueOf(5.0));
                assertThat(response.message()).contains("Approved");

                List<OrderPriceOverrideApplied> events = applicationEvents
                                .stream(OrderPriceOverrideApplied.class)
                                .toList();
                assertThat(events).hasSize(1);
                assertThat(events.get(0).overrideId()).isEqualTo(response.overrideId());
        }

        @Test
        void testApplyPriceOverride_LargeDiscount_RequiresApproval() {
                // Given: Large discount (20%) that requires approval
                createOrderWithLine(
                                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                UUID.fromString("00000000-0000-0000-0000-000000000009"),
                                BigDecimal.valueOf(1000.00));

                ApplyPriceOverrideRequest request = new ApplyPriceOverrideRequest();
                request.setOrderId(UUID.fromString("00000000-0000-0000-0000-000000000001").toString());
                request.setOrderLineId(UUID.fromString("00000000-0000-0000-0000-000000000009").toString());
                request.setProductId(UUID.fromString("00000000-0000-0000-0000-000000000011").toString());
                request.setOriginalPrice(BigDecimal.valueOf(1000.00));
                request.setOverridePrice(BigDecimal.valueOf(800.00));
                request.setReasonCode("PRICE_MATCH");
                request.setJustification("Competitor match");

                // When
                PriceOverrideResult response = priceOverrideService.applyPriceOverride(request);

                // Then
                assertThat(response).isNotNull();
                assertThat(response.status()).isEqualTo(OverrideStatus.PENDING_APPROVAL.name());
                assertThat(response.requiresApproval()).isTrue();
                assertThat(response.message()).contains("Pending manager approval");
        }

        @Test
        void testApplyPriceOverride_WithIdempotencyKey_ReturnsSameOverride() {
                String idempotencyKey = "idem-key-" + UUID.randomUUID();
                createOrderWithLine(
                                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                UUID.fromString("00000000-0000-0000-0000-000000000009"),
                                BigDecimal.valueOf(100.00));

                ApplyPriceOverrideRequest request = new ApplyPriceOverrideRequest();
                request.setOrderId(UUID.fromString("00000000-0000-0000-0000-000000000001").toString());
                request.setOrderLineId(UUID.fromString("00000000-0000-0000-0000-000000000009").toString());
                request.setProductId(UUID.fromString("00000000-0000-0000-0000-000000000011").toString());
                request.setOriginalPrice(BigDecimal.valueOf(100.00));
                request.setOverridePrice(BigDecimal.valueOf(95.00));
                request.setReasonCode("CUSTOMER_LOYALTY");
                request.setJustification("Loyal customer discount");
                request.setIdempotencyKey(idempotencyKey);

                int sizeBefore = priceOverrideRepository.findAll().size();

                PriceOverrideResult first = priceOverrideService.applyPriceOverride(request);
                PriceOverrideResult second = priceOverrideService.applyPriceOverride(request);

                assertThat(second.overrideId()).isEqualTo(first.overrideId());
                int sizeAfter = priceOverrideRepository.findAll().size();
                assertThat(sizeAfter).isEqualTo(sizeBefore + 1);
        }

        @Test
        void testApplyPriceOverride_WithIdempotencyKeyDifferentPayload_ThrowsConflict() {
                String idempotencyKey = "idem-key-" + UUID.randomUUID();
                createOrderWithLine(
                                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                UUID.fromString("00000000-0000-0000-0000-000000000009"),
                                BigDecimal.valueOf(100.00));

                ApplyPriceOverrideRequest firstRequest = new ApplyPriceOverrideRequest();
                firstRequest.setOrderId(UUID.fromString("00000000-0000-0000-0000-000000000001").toString());
                firstRequest.setOrderLineId(UUID.fromString("00000000-0000-0000-0000-000000000009").toString());
                firstRequest.setProductId(UUID.fromString("00000000-0000-0000-0000-000000000011").toString());
                firstRequest.setOriginalPrice(BigDecimal.valueOf(100.00));
                firstRequest.setOverridePrice(BigDecimal.valueOf(95.00));
                firstRequest.setReasonCode("CUSTOMER_LOYALTY");
                firstRequest.setJustification("Loyal customer discount");
                firstRequest.setIdempotencyKey(idempotencyKey);

                ApplyPriceOverrideRequest secondRequest = new ApplyPriceOverrideRequest();
                secondRequest.setOrderId(UUID.fromString("00000000-0000-0000-0000-000000000001").toString());
                secondRequest.setOrderLineId(UUID.fromString("00000000-0000-0000-0000-000000000009").toString());
                secondRequest.setProductId(UUID.fromString("00000000-0000-0000-0000-000000000011").toString());
                secondRequest.setOriginalPrice(BigDecimal.valueOf(100.00));
                secondRequest.setOverridePrice(BigDecimal.valueOf(94.00));
                secondRequest.setReasonCode("CUSTOMER_LOYALTY");
                secondRequest.setJustification("Loyal customer discount");
                secondRequest.setIdempotencyKey(idempotencyKey);

                priceOverrideService.applyPriceOverride(firstRequest);

                assertThatThrownBy(() -> priceOverrideService.applyPriceOverride(secondRequest))
                                .isInstanceOf(PriceOverrideIdempotencyConflictException.class)
                                .hasMessageContaining("Idempotency key was already used");
        }

        @Test
        void testApplyPriceOverride_OverridePriceGreaterThanOriginal_ThrowsException() {
                // Given: Override price greater than original
                ApplyPriceOverrideRequest request = new ApplyPriceOverrideRequest();
                request.setOrderId(UUID.fromString("00000000-0000-0000-0000-000000000001").toString());
                request.setOrderLineId(UUID.fromString("00000000-0000-0000-0000-000000000009").toString());
                request.setProductId(UUID.fromString("00000000-0000-0000-0000-000000000011").toString());
                request.setOriginalPrice(BigDecimal.valueOf(100.00));
                request.setOverridePrice(BigDecimal.valueOf(150.00));
                request.setReasonCode("OTHER");

                // When/Then
                assertThatThrownBy(() -> priceOverrideService.applyPriceOverride(request))
                                .isInstanceOf(InvalidPriceOverrideException.class)
                                .hasMessageContaining("cannot be greater than original price");
        }

        @Test
        void testApplyPriceOverride_AutoApproved_MissingOrderLine_ThrowsException() {
                SalesOrder order = SalesOrder.builder()
                                .orderId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .clerkId("clerk-1")
                                .terminalId("terminal-1")
                                .status(SalesOrderStatus.DRAFT)
                                .subtotal(BigDecimal.ZERO)
                                .createdBy("test-user")
                                .updatedBy("test-user")
                                .build();
                salesOrderRepository.save(order);

                ApplyPriceOverrideRequest request = new ApplyPriceOverrideRequest();
                request.setOrderId(UUID.fromString("00000000-0000-0000-0000-000000000001").toString());
                request.setOrderLineId(UUID.fromString("00000000-0000-0000-0000-000000000099").toString());
                request.setProductId(UUID.fromString("00000000-0000-0000-0000-000000000011").toString());
                request.setOriginalPrice(BigDecimal.valueOf(100.00));
                request.setOverridePrice(BigDecimal.valueOf(95.00));
                request.setReasonCode("CUSTOMER_LOYALTY");
                request.setJustification("Loyal customer discount");

                assertThatThrownBy(() -> priceOverrideService.applyPriceOverride(request))
                                .isInstanceOf(InvalidPriceOverrideException.class)
                                .hasMessageContaining("Order line not found");

                List<OrderPriceOverrideApplied> events = applicationEvents.stream(OrderPriceOverrideApplied.class)
                                .toList();
                assertThat(events).isEmpty();
        }

        @Test
        void testApplyPriceOverride_AutoApproved_MissingOrder_ThrowsException() {
                createOrderWithLine(
                                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                UUID.fromString("00000000-0000-0000-0000-000000000009"),
                                BigDecimal.valueOf(100.00));

                ApplyPriceOverrideRequest request = new ApplyPriceOverrideRequest();
                request.setOrderId(UUID.fromString("00000000-0000-0000-0000-000000000099").toString());
                request.setOrderLineId(UUID.fromString("00000000-0000-0000-0000-000000000009").toString());
                request.setProductId(UUID.fromString("00000000-0000-0000-0000-000000000011").toString());
                request.setOriginalPrice(BigDecimal.valueOf(100.00));
                request.setOverridePrice(BigDecimal.valueOf(95.00));
                request.setReasonCode("CUSTOMER_LOYALTY");
                request.setJustification("Loyal customer discount");

                assertThatThrownBy(() -> priceOverrideService.applyPriceOverride(request))
                                .isInstanceOf(InvalidPriceOverrideException.class)
                                .hasMessageContaining("Order not found");

                List<OrderPriceOverrideApplied> events = applicationEvents.stream(OrderPriceOverrideApplied.class)
                                .toList();
                assertThat(events).isEmpty();
        }

        @Test
        void testApprovePriceOverride_Success() {
                // Given: Pending override
                PriceOverride override = createPendingOverride();
                UUID managerUserId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                setAuthenticatedUser(managerUserId.toString());

                // When
                PriceOverrideDetail approved = priceOverrideService.approveOverride(
                                override.getOverrideId(),
                                new ApproveOverrideCommand("MANAGER", "Approved for customer retention"));

                // Then
                assertThat(approved.status()).isEqualTo(OverrideStatus.APPROVED.name());
                assertThat(approved.approvedByUserId()).isEqualTo(managerUserId.toString());
                assertThat(approved.approvedAt()).isNotNull();

                // Verify approval record created
                assertThat(approvalRecordRepository.findByPriceOverride_OverrideId(override.getOverrideId()))
                                .hasSize(1)
                                .first()
                                .satisfies(reccord -> {
                                        assertThat(reccord.getReviewerUserId()).isEqualTo(managerUserId);
                                        assertThat(reccord.getReviewerRole()).isEqualTo("MANAGER");
                                        assertThat(reccord.getAction()).isEqualTo("APPROVED");
                                });
        }

        @Test
        void testRejectPriceOverride_Success() {
                // Given: Pending override
                PriceOverride override = createPendingOverride();
                UUID managerUserId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                setAuthenticatedUser(managerUserId.toString());

                // When
                PriceOverrideDetail rejected = priceOverrideService.rejectOverride(
                                override.getOverrideId(),
                                new RejectOverrideCommand("MANAGER", "Discount too high",
                                                "Please revise and resubmit"));

                // Then
                assertThat(rejected.status()).isEqualTo(OverrideStatus.REJECTED.name());
                assertThat(rejected.rejectedByUserId()).isEqualTo(managerUserId.toString());
                assertThat(rejected.rejectionReason()).isEqualTo("Discount too high");
                assertThat(rejected.rejectedAt()).isNotNull();

                // Verify approval record created
                assertThat(approvalRecordRepository.findByPriceOverride_OverrideId(override.getOverrideId()))
                                .hasSize(1)
                                .first()
                                .satisfies(reccord -> {
                                        assertThat(reccord.getAction()).isEqualTo("REJECTED");
                                });
        }

        @Test
        void testGetOverrideById_Found() {
                // Given: Existing override
                PriceOverride override = createPendingOverride();

                // When
                PriceOverrideDetail found = priceOverrideService.getOverrideById(override.getOverrideId());

                // Then
                assertThat(found).isNotNull();
                assertThat(found.overrideId()).isEqualTo(override.getOverrideId());
        }

        @Test
        void testGetOverrideById_NotFound() {
                // When/Then
                assertThatThrownBy(
                                () -> priceOverrideService.getOverrideById(
                                                UUID.fromString("00000000-0000-0000-0000-000000000001")))
                                .isInstanceOf(PriceOverrideNotFoundException.class);
        }

        @Test
        void testGetOverridesByOrderId() {
                // Given: Multiple overrides for same order
                UUID orderId1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID orderId2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
                createOverride(orderId1, UUID.fromString("00000000-0000-0000-0000-000000000003"),
                                OverrideStatus.APPROVED);
                createOverride(orderId1, UUID.fromString("00000000-0000-0000-0000-000000000004"),
                                OverrideStatus.PENDING_APPROVAL);
                createOverride(orderId2, UUID.fromString("00000000-0000-0000-0000-000000000005"),
                                OverrideStatus.APPROVED);

                // When
                List<PriceOverrideDetail> overrides = priceOverrideService.getOverridesByOrderId(orderId1);

                // Then
                assertThat(overrides).hasSize(2);
        }

        @Test
        void testGetPendingApprovals() {
                // Given: Mixed status overrides
                createOverride(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                UUID.fromString("00000000-0000-0000-0000-000000000003"), OverrideStatus.APPROVED);
                createOverride(UUID.fromString("00000000-0000-0000-0000-000000000004"),
                                UUID.fromString("00000000-0000-0000-0000-000000000005"),
                                OverrideStatus.PENDING_APPROVAL);
                createOverride(UUID.fromString("00000000-0000-0000-0000-000000000006"),
                                UUID.fromString("00000000-0000-0000-0000-000000000007"),
                                OverrideStatus.PENDING_APPROVAL);
                createOverride(UUID.fromString("00000000-0000-0000-0000-000000000009"),
                                UUID.fromString("00000000-0000-0000-0000-000000000011"), OverrideStatus.REJECTED);

                // When
                List<PriceOverrideDetail> pending = priceOverrideService.getPendingApprovals();

                // Then
                assertThat(pending).hasSize(2);
                assertThat(pending).allMatch(o -> o.status().equals(OverrideStatus.PENDING_APPROVAL.name()));
        }

        @Test
        void testGetOverridesByStatus() {
                // Given: Mixed status overrides
                createOverride(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                UUID.fromString("00000000-0000-0000-0000-000000000002"), OverrideStatus.APPROVED);
                createOverride(UUID.fromString("00000000-0000-0000-0000-000000000003"),
                                UUID.fromString("00000000-0000-0000-0000-000000000004"), OverrideStatus.APPROVED);
                createOverride(UUID.fromString("00000000-0000-0000-0000-000000000005"),
                                UUID.fromString("00000000-0000-0000-0000-000000000006"), OverrideStatus.REJECTED);

                // When
                List<PriceOverrideDetail> approved = priceOverrideService.getOverridesByStatus("APPROVED");

                // Then
                assertThat(approved).hasSize(2);
        }

        // Helper methods

        private PriceOverride createPendingOverride() {
                return createOverride(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                                OverrideStatus.PENDING_APPROVAL);
        }

        private PriceOverride createOverride(UUID orderId, UUID orderLineId, OverrideStatus status) {
                SalesOrder order = salesOrderRepository.findById(orderId)
                                .orElseGet(() -> salesOrderRepository.save(SalesOrder.builder()
                                                .orderId(orderId)
                                                .clerkId("clerk-1")
                                                .terminalId("terminal-1")
                                                .status(SalesOrderStatus.DRAFT)
                                                .subtotal(BigDecimal.valueOf(100.00).setScale(4))
                                                .createdBy("test-user")
                                                .updatedBy("test-user")
                                                .build()));

                SalesOrderLine line = salesOrderLineRepository.findById(orderLineId)
                                .orElseGet(() -> salesOrderLineRepository.save(SalesOrderLine.builder()
                                                .orderLineId(orderLineId)
                                                .order(order)
                                                .itemSku("SKU-TEST")
                                                .itemDescription("Test item")
                                                .quantity(1)
                                                .unitPrice(BigDecimal.valueOf(100.00).setScale(4))
                                                .fulfillmentStatus(FulfillmentStatus.AVAILABLE)
                                                .priceSource(PriceSource.MANUAL)
                                                .build()));

                PriceOverride override = PriceOverride.builder()
                                .order(order)
                                .orderLine(line)
                                .productId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .originalPrice(BigDecimal.valueOf(100.00))
                                .overridePrice(BigDecimal.valueOf(80.00))
                                .reasonCode(PriceOverrideReasonCode.CUSTOMER_LOYALTY)
                                .justification("Test override")
                                .status(status)
                                .requiresApproval(status == OverrideStatus.PENDING_APPROVAL)
                                .requestedByUserId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                                .createdBy("test-user")
                                .updatedBy("test-user")
                                .build();

                return priceOverrideRepository.save(override);
        }

        private SalesOrderLine createOrderWithLine(UUID orderId, UUID orderLineId, BigDecimal unitPrice) {
                SalesOrder order = SalesOrder.builder()
                                .orderId(orderId)
                                .clerkId("clerk-1")
                                .terminalId("terminal-1")
                                .status(SalesOrderStatus.DRAFT)
                                .subtotal(unitPrice.setScale(4))
                                .createdBy("test-user")
                                .updatedBy("test-user")
                                .build();
                salesOrderRepository.save(order);

                SalesOrderLine line = SalesOrderLine.builder()
                                .orderLineId(orderLineId)
                                .order(order)
                                .itemSku("SKU-1")
                                .itemDescription("Test item")
                                .quantity(1)
                                .unitPrice(unitPrice.setScale(4))
                                .fulfillmentStatus(FulfillmentStatus.AVAILABLE)
                                .priceSource(PriceSource.MANUAL)
                                .build();
                return salesOrderLineRepository.save(line);
        }

        private void setAuthenticatedUser(String username) {
                var authentication = new UsernamePasswordAuthenticationToken(username, "N/A", List.of());
                authentication.setDetails(Map.of(
                                GatewaySecurityConstants.DETAIL_USERNAME, username,
                                GatewaySecurityConstants.DETAIL_USER_ID, UUID.fromString(username)));
                SecurityContextHolder.getContext().setAuthentication(authentication);
        }
}
