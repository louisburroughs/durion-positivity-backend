package com.positivity.order.service;

import com.positivity.order.config.TestSecurityConfig;
import com.positivity.order.internal.entity.OverrideStatus;
import com.positivity.order.internal.entity.PriceOverride;
import com.positivity.order.internal.entity.PriceOverrideReasonCode;
import com.positivity.order.internal.event.OrderPriceOverrideApplied;
import com.positivity.order.internal.exception.InvalidPriceOverrideException;
import com.positivity.order.internal.exception.PriceOverrideNotFoundException;
import com.positivity.order.internal.repository.ApprovalRecordRepository;
import com.positivity.order.internal.repository.PriceOverrideRepository;
import com.positivity.order.service.model.ApplyPriceOverrideRequest;
import com.positivity.order.service.model.ApproveOverrideCommand;
import com.positivity.order.service.model.PriceOverrideDetail;
import com.positivity.order.service.model.PriceOverrideResult;
import com.positivity.order.service.model.RejectOverrideCommand;
import java.math.BigDecimal;
import java.util.List;
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
        private ApplicationEvents applicationEvents;

        @BeforeEach
        void setUp() {
                priceOverrideRepository.deleteAll();
                approvalRecordRepository.deleteAll();
        }

        @AfterEach
        void tearDown() {
                SecurityContextHolder.clearContext();
        }

        @Test
        void testApplyPriceOverride_SmallDiscount_AutoApproved() {
                // Given: Small discount (5%) that doesn't require approval
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
                assertThat(approvalRecordRepository.findByPriceOverrideId(override.getOverrideId()))
                                .hasSize(1)
                                .first()
                                .satisfies(record -> {
                                        assertThat(record.getReviewerUserId()).isEqualTo(managerUserId);
                                        assertThat(record.getReviewerRole()).isEqualTo("MANAGER");
                                        assertThat(record.getAction()).isEqualTo("APPROVED");
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
                assertThat(approvalRecordRepository.findByPriceOverrideId(override.getOverrideId()))
                                .hasSize(1)
                                .first()
                                .satisfies(record -> {
                                        assertThat(record.getAction()).isEqualTo("REJECTED");
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
                PriceOverride override = PriceOverride.builder()
                                .orderId(orderId)
                                .orderLineId(orderLineId)
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

        private void setAuthenticatedUser(String username) {
                var authentication = new UsernamePasswordAuthenticationToken(username, "N/A", List.of());
                SecurityContextHolder.getContext().setAuthentication(authentication);
        }
}
