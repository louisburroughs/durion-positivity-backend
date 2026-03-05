package com.positivity.order.service;

import com.positivity.order.config.TestSecurityConfig;
import com.positivity.order.internal.dto.ApplyPriceOverrideRequest;
import com.positivity.order.internal.dto.ApplyPriceOverrideResponse;
import com.positivity.order.internal.dto.ApprovePriceOverrideRequest;
import com.positivity.order.internal.dto.RejectPriceOverrideRequest;
import com.positivity.order.internal.entity.OverrideStatus;
import com.positivity.order.internal.entity.PriceOverride;
import com.positivity.order.internal.entity.PriceOverrideReasonCode;
import com.positivity.order.internal.exception.InvalidPriceOverrideException;
import com.positivity.order.internal.exception.PriceOverrideNotFoundException;
import com.positivity.order.internal.repository.ApprovalRecordRepository;
import com.positivity.order.internal.repository.PriceOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for PriceOverrideService.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestSecurityConfig.class)
class PriceOverrideServiceTest {

    @Autowired
    private PriceOverrideService priceOverrideService;

    @Autowired
    private PriceOverrideRepository priceOverrideRepository;

    @Autowired
    private ApprovalRecordRepository approvalRecordRepository;

    @BeforeEach
    void setUp() {
        priceOverrideRepository.deleteAll();
        approvalRecordRepository.deleteAll();
    }

    @Test
    void testApplyPriceOverride_SmallDiscount_AutoApproved() {
        // Given: Small discount (5%) that doesn't require approval
        ApplyPriceOverrideRequest request = new ApplyPriceOverrideRequest();
        request.setOrderId(UUID.fromString("00000000-0000-0000-0000-000000000001").toString());
        request.setOrderLineId(UUID.fromString("00000000-0000-0000-0000-000000000001").toString());
        request.setProductId(UUID.fromString("00000000-0000-0000-0000-000000000001").toString());
        request.setOriginalPrice(BigDecimal.valueOf(100.00));
        request.setOverridePrice(BigDecimal.valueOf(95.00));
        request.setReasonCode(PriceOverrideReasonCode.CUSTOMER_LOYALTY);
        request.setJustification("Loyal customer discount");

        // When
        ApplyPriceOverrideResponse response = priceOverrideService.applyPriceOverride(request,
                UUID.fromString("00000000-0000-0000-0000-000000000001").toString());

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getOverrideId()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(OverrideStatus.APPROVED);
        assertThat(response.getRequiresApproval()).isFalse();
        assertThat(response.getDiscountAmount()).isEqualByComparingTo(BigDecimal.valueOf(5.00));
        assertThat(response.getDiscountPercentage()).isEqualByComparingTo(BigDecimal.valueOf(5.0));
        assertThat(response.getMessage()).contains("approved");
    }

    @Test
    void testApplyPriceOverride_LargeDiscount_RequiresApproval() {
        // Given: Large discount (20%) that requires approval
        ApplyPriceOverrideRequest request = new ApplyPriceOverrideRequest();
        request.setOrderId(UUID.fromString("00000000-0000-0000-0000-000000000001").toString());
        request.setOrderLineId(UUID.fromString("00000000-0000-0000-0000-000000000001").toString());
        request.setProductId(UUID.fromString("00000000-0000-0000-0000-000000000001").toString());
        request.setOriginalPrice(BigDecimal.valueOf(1000.00));
        request.setOverridePrice(BigDecimal.valueOf(800.00));
        request.setReasonCode(PriceOverrideReasonCode.PRICE_MATCH);
        request.setJustification("Competitor match");

        // When
        ApplyPriceOverrideResponse response = priceOverrideService.applyPriceOverride(request,
                UUID.fromString("00000000-0000-0000-0000-000000000001").toString());

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(OverrideStatus.PENDING_APPROVAL);
        assertThat(response.getRequiresApproval()).isTrue();
        assertThat(response.getMessage()).contains("pending manager approval");
    }

    @Test
    void testApplyPriceOverride_OverridePriceGreaterThanOriginal_ThrowsException() {
        // Given: Override price greater than original
        ApplyPriceOverrideRequest request = new ApplyPriceOverrideRequest();
        request.setOrderId(UUID.fromString("00000000-0000-0000-0000-000000000001").toString());
        request.setOrderLineId(UUID.fromString("00000000-0000-0000-0000-000000000001").toString());
        request.setProductId(UUID.fromString("00000000-0000-0000-0000-000000000001").toString());
        request.setOriginalPrice(BigDecimal.valueOf(100.00));
        request.setOverridePrice(BigDecimal.valueOf(150.00));
        request.setReasonCode(PriceOverrideReasonCode.OTHER);

        // When/Then
        assertThatThrownBy(() -> priceOverrideService.applyPriceOverride(request, "user789"))
                .isInstanceOf(InvalidPriceOverrideException.class)
                .hasMessageContaining("cannot be greater than original price");
    }

    @Test
    void testApprovePriceOverride_Success() {
        // Given: Pending override
        PriceOverride override = createPendingOverride();
        UUID managerUserId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        // When
        ApprovePriceOverrideRequest approveRequest = new ApprovePriceOverrideRequest();
        approveRequest.setComments("Approved for customer retention");

        PriceOverride approved = priceOverrideService.approvePriceOverride(
                override.getOverrideId(), approveRequest, managerUserId, "MANAGER");

        // Then
        assertThat(approved.getStatus()).isEqualTo(OverrideStatus.APPROVED);
        assertThat(approved.getApprovedByUserId()).isEqualTo(managerUserId);
        assertThat(approved.getApprovedAt()).isNotNull();

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

        // When
        RejectPriceOverrideRequest rejectRequest = new RejectPriceOverrideRequest();
        rejectRequest.setReason("Discount too high");
        rejectRequest.setComments("Please revise and resubmit");

        PriceOverride rejected = priceOverrideService.rejectPriceOverride(
                override.getOverrideId(), rejectRequest, managerUserId, "MANAGER");

        // Then
        assertThat(rejected.getStatus()).isEqualTo(OverrideStatus.REJECTED);
        assertThat(rejected.getRejectedByUserId()).isEqualTo(managerUserId);
        assertThat(rejected.getRejectionReason()).isEqualTo("Discount too high");
        assertThat(rejected.getRejectedAt()).isNotNull();

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
        PriceOverride found = priceOverrideService.getOverrideById(override.getOverrideId());

        // Then
        assertThat(found).isNotNull();
        assertThat(found.getOverrideId()).isEqualTo(override.getOverrideId());
    }

    @Test
    void testGetOverrideById_NotFound() {
        // When/Then
        assertThatThrownBy(
                () -> priceOverrideService.getOverrideById(UUID.fromString("00000000-0000-0000-0000-000000000001")))
                .isInstanceOf(PriceOverrideNotFoundException.class);
    }

    @Test
    void testGetOverridesByOrderId() {
        // Given: Multiple overrides for same order
        UUID orderId1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID orderId2 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        createOverride(orderId1, UUID.fromString("00000000-0000-0000-0000-000000000001"), OverrideStatus.APPROVED);
        createOverride(orderId1, UUID.fromString("00000000-0000-0000-0000-000000000001"),
                OverrideStatus.PENDING_APPROVAL);
        createOverride(orderId2, UUID.fromString("00000000-0000-0000-0000-000000000001"), OverrideStatus.APPROVED);

        // When
        List<PriceOverride> overrides = priceOverrideService.getOverridesByOrderId(orderId1);

        // Then
        assertThat(overrides).hasSize(2);
    }

    @Test
    void testGetPendingApprovals() {
        // Given: Mixed status overrides
        createOverride(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"), OverrideStatus.APPROVED);
        createOverride(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"), OverrideStatus.PENDING_APPROVAL);
        createOverride(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"), OverrideStatus.PENDING_APPROVAL);
        createOverride(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"), OverrideStatus.REJECTED);

        // When
        List<PriceOverride> pending = priceOverrideService.getPendingApprovals();

        // Then
        assertThat(pending).hasSize(2);
        assertThat(pending).allMatch(o -> o.getStatus() == OverrideStatus.PENDING_APPROVAL);
    }

    @Test
    void testGetOverridesByStatus() {
        // Given: Mixed status overrides
        createOverride(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"), OverrideStatus.APPROVED);
        createOverride(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"), OverrideStatus.APPROVED);
        createOverride(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"), OverrideStatus.REJECTED);

        // When
        List<PriceOverride> approved = priceOverrideService.getOverridesByStatus(OverrideStatus.APPROVED);

        // Then
        assertThat(approved).hasSize(2);
    }

    // Helper methods

    private PriceOverride createPendingOverride() {
        return createOverride(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"), OverrideStatus.PENDING_APPROVAL);
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
                .requestedByUserId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .build();

        return priceOverrideRepository.save(override);
    }
}
