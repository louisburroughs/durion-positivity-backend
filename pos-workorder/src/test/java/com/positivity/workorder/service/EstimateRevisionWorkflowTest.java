package com.positivity.workorder.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.workorder.internal.client.TaxClient;
import com.positivity.workorder.internal.entity.AuditEvent;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.EstimateStatus;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.AuditEventRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for the Estimate Revision and Approval Invalidation
 * workflow.
 *
 * These tests verify that when an Estimate's financial total changes,
 * associated Workorders in APPROVED status are automatically transitioned
 * to AWAITING_APPROVAL status, requiring re-approval.
 *
 * Covers Issue #203: Approval: Invalidate Approval on Estimate Revision
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EstimateRevisionWorkflowTest {

    @Autowired
    private EstimateService estimateService;

    @Autowired
    private EstimateRepository estimateRepository;

    @Autowired
    private WorkorderRepository workorderRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @MockitoBean
    private TaxClient taxClient;

    private Estimate testEstimate;
    private Workorder testWorkorder;
    private static final UUID TEST_USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");

    @BeforeEach
    void mockAuthentication() {
        TestingAuthenticationToken authentication =
                new TestingAuthenticationToken("test-user", "password", "ROLE_USER");

        authentication.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USER_ID,
                TEST_USER_ID,
                GatewaySecurityConstants.DETAIL_USERNAME,
                "test-user"));

        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void setUp() {
        // Clean up any existing test data
        auditEventRepository.deleteAll();
        workorderRepository.deleteAll();
        estimateRepository.deleteAll();
    }

    /**
     * Test Scenario 1: Estimate total increases due to adding a line item
     *
     * Given: A Workorder in APPROVED status linked to an Estimate totaling $500.00
     * When: The estimate total is increased to $650.00
     * Then: Workorder status must automatically transition to AWAITING_APPROVAL
     * And: An audit event must be created capturing the change
     */
    @Test
    void testApprovalInvalidatedWhenEstimateTotalIncreases() {
        // Given: Create estimate with initial total
        testEstimate = createTestEstimate(
                new BigDecimal("475.00"), // subtotal
                new BigDecimal("25.00"), // tax
                new BigDecimal("500.00") // total
                );

        // Create work order in APPROVED status
        testWorkorder = createApprovedWorkorder(testEstimate.getId());

        // When: Update estimate with higher total (simulating adding a line item)
        estimateService.updateEstimateFinancials(
                testEstimate.getId(),
                new BigDecimal("600.00"), // new subtotal
                new BigDecimal("50.00"), // new tax
                new BigDecimal("650.00"), // new total
                TEST_USER_ID.toString());

        // Then: Workorder status should be AWAITING_APPROVAL
        Workorder updatedWorkorder =
                workorderRepository.findById(testWorkorder.getId()).orElseThrow();
        assertThat(updatedWorkorder.getStatus())
                .as("Workorder should transition to AWAITING_APPROVAL after estimate increase")
                .isEqualTo(WorkorderStatus.AWAITING_APPROVAL);

        // And: Audit event should be created
        List<AuditEvent> auditEvents = auditEventRepository.findAll();
        assertThat(auditEvents).as("Should have created an audit event").hasSize(1);

        AuditEvent audit = auditEvents.get(0);
        assertThat(audit.getEntityType()).isEqualTo("Workorder");
        assertThat(audit.getEntityId()).isEqualTo(testWorkorder.getId());
        assertThat(audit.getEventType()).isEqualTo("approval.invalidated");
        assertThat(audit.getUserId()).isEqualTo("test-user");
        assertThat(audit.getDetails())
                .contains("500.00") // old total
                .contains("650.00") // new total
                .contains("Previous status: APPROVED")
                .contains("New status: AWAITING_APPROVAL");
    }

    /**
     * Test Scenario 2: Estimate total decreases due to changing a part price
     *
     * Given: A Workorder in APPROVED status linked to an Estimate totaling $500.00
     * When: The estimate total is decreased to $450.00
     * Then: Workorder status must still transition to AWAITING_APPROVAL
     * (Any change requires re-approval, regardless of direction)
     */
    @Test
    void testApprovalInvalidatedWhenEstimateTotalDecreases() {
        // Given
        testEstimate = createTestEstimate(new BigDecimal("475.00"), new BigDecimal("25.00"), new BigDecimal("500.00"));
        testWorkorder = createApprovedWorkorder(testEstimate.getId());

        // When: Decrease total
        estimateService.updateEstimateFinancials(
                testEstimate.getId(),
                new BigDecimal("425.00"), // lower subtotal
                new BigDecimal("25.00"),
                new BigDecimal("450.00"), // lower total
                "test-user");

        // Then
        Workorder updatedWorkorder =
                workorderRepository.findById(testWorkorder.getId()).orElseThrow();
        assertThat(updatedWorkorder.getStatus())
                .as("Workorder should transition to AWAITING_APPROVAL even when estimate decreases")
                .isEqualTo(WorkorderStatus.AWAITING_APPROVAL);
    }

    /**
     * Test Scenario 3: Non-financial change to estimate
     *
     * Given: A Workorder in APPROVED status
     * When: The estimate is updated but total remains unchanged
     * Then: Workorder status must remain APPROVED
     */
    @Test
    void testApprovalNotInvalidatedWhenEstimateTotalUnchanged() {
        // Given
        testEstimate = createTestEstimate(new BigDecimal("475.00"), new BigDecimal("25.00"), new BigDecimal("500.00"));
        testWorkorder = createApprovedWorkorder(testEstimate.getId());

        // When: Update with different breakdown but same total
        estimateService.updateEstimateFinancials(
                testEstimate.getId(),
                new BigDecimal("480.00"), // changed subtotal
                new BigDecimal("20.00"), // changed tax
                new BigDecimal("500.00"), // same total
                TEST_USER_ID.toString());

        // Then: Status should remain APPROVED
        Workorder updatedWorkorder =
                workorderRepository.findById(testWorkorder.getId()).orElseThrow();
        assertThat(updatedWorkorder.getStatus())
                .as("Workorder should remain APPROVED when estimate total is unchanged")
                .isEqualTo(WorkorderStatus.APPROVED);

        // And: No audit events should be created
        List<AuditEvent> auditEvents = auditEventRepository.findAll();
        assertThat(auditEvents)
                .as("Should not create audit event when total unchanged")
                .isEmpty();
    }

    /**
     * Test Scenario 4: Approval invalidation only affects APPROVED Workorders
     *
     * Given: A Workorder in WORK_IN_PROGRESS status
     * When: The linked estimate total changes
     * Then: Workorder status should remain WORK_IN_PROGRESS (not invalidated)
     */
    @Test
    void testApprovalInvalidationOnlyAffectsApprovedWorkorders() {
        // Given: Workorder in different status
        testEstimate = createTestEstimate(new BigDecimal("475.00"), new BigDecimal("25.00"), new BigDecimal("500.00"));

        testWorkorder = createTestWorkorder(testEstimate.getId());
        testWorkorder.setStatus(WorkorderStatus.WORK_IN_PROGRESS);
        testWorkorder = workorderRepository.save(testWorkorder);

        // When: Update estimate total
        estimateService.updateEstimateFinancials(
                testEstimate.getId(),
                new BigDecimal("600.00"),
                new BigDecimal("50.00"),
                new BigDecimal("650.00"),
                TEST_USER_ID.toString());

        // Then: Status should remain unchanged
        Workorder updatedWorkorder =
                workorderRepository.findById(testWorkorder.getId()).orElseThrow();
        assertThat(updatedWorkorder.getStatus())
                .as("Workorder not in APPROVED status should not be affected")
                .isEqualTo(WorkorderStatus.WORK_IN_PROGRESS);

        // And: No audit events for approval invalidation
        List<AuditEvent> auditEvents = auditEventRepository.findAll();
        assertThat(auditEvents)
                .as("Should not create audit event for non-approved work orders")
                .isEmpty();
    }

    /**
     * Test: Multiple Workorders linked to same estimate
     *
     * Given: Two Workorders both in APPROVED status, linked to same Estimate
     * When: The estimate total changes
     * Then: Both Workorders should transition to AWAITING_APPROVAL
     */
    @Test
    void testMultipleWorkordersInvalidatedForSameEstimate() {
        // Given: Two work orders for same estimate
        testEstimate = createTestEstimate(new BigDecimal("475.00"), new BigDecimal("25.00"), new BigDecimal("500.00"));

        Workorder workorder1 = createApprovedWorkorder(testEstimate.getId());
        Workorder workorder2 = createApprovedWorkorder(testEstimate.getId());

        // When: Update estimate
        estimateService.updateEstimateFinancials(
                testEstimate.getId(),
                new BigDecimal("600.00"),
                new BigDecimal("50.00"),
                new BigDecimal("650.00"),
                TEST_USER_ID.toString());

        // Then: Both should be invalidated
        Workorder updated1 = workorderRepository.findById(workorder1.getId()).orElseThrow();
        Workorder updated2 = workorderRepository.findById(workorder2.getId()).orElseThrow();

        assertThat(updated1.getStatus()).isEqualTo(WorkorderStatus.AWAITING_APPROVAL);
        assertThat(updated2.getStatus()).isEqualTo(WorkorderStatus.AWAITING_APPROVAL);

        // And: Two audit events should be created
        List<AuditEvent> auditEvents = auditEventRepository.findAll();
        assertThat(auditEvents).hasSize(2);
    }

    /**
     * Test: Estimate version increments on financial changes
     *
     * Given: An estimate with version 1
     * When: The estimate total is updated
     * Then: The version should increment to 2
     */
    @Test
    void testEstimateVersionIncrementsOnFinancialChange() {
        // Given
        testEstimate = createTestEstimate(new BigDecimal("475.00"), new BigDecimal("25.00"), new BigDecimal("500.00"));

        assertThat(testEstimate.getVersion())
                .as("Initial estimate version should be 1")
                .isEqualTo(1);

        // When: Update with different total
        estimateService.updateEstimateFinancials(
                testEstimate.getId(),
                new BigDecimal("600.00"),
                new BigDecimal("50.00"),
                new BigDecimal("650.00"),
                TEST_USER_ID.toString());

        // Then: Version should increment
        Estimate updated = estimateRepository.findById(testEstimate.getId()).orElseThrow();
        assertThat(updated.getVersion())
                .as("Estimate version should increment after financial change")
                .isEqualTo(2);
    }

    /**
     * Test: Estimate version does not increment on non-financial changes
     */
    @Test
    void testEstimateVersionUnchangedWhenTotalUnchanged() {
        // Given
        testEstimate = createTestEstimate(new BigDecimal("475.00"), new BigDecimal("25.00"), new BigDecimal("500.00"));

        // When: Update with same total
        estimateService.updateEstimateFinancials(
                testEstimate.getId(),
                new BigDecimal("480.00"),
                new BigDecimal("20.00"),
                new BigDecimal("500.00"), // same total
                TEST_USER_ID.toString());

        // Then: Version should not increment
        Estimate updated = estimateRepository.findById(testEstimate.getId()).orElseThrow();
        assertThat(updated.getVersion())
                .as("Estimate version should not change when total is unchanged")
                .isEqualTo(1);
    }

    // Helper methods

    private Estimate createTestEstimate(BigDecimal subtotal, BigDecimal taxAmount, BigDecimal total) {
        Estimate estimate = Estimate.builder()
                .estimateNumber("TEST-EST-" + System.currentTimeMillis())
                .locationId(UUID.fromString("550e8400-e29b-41d4-a716-446655440050"))
                .customerId(UUID.fromString("550e8400-e29b-41d4-a716-446655440051"))
                .vehicleId(UUID.fromString("550e8400-e29b-41d4-a716-446655440052"))
                .status(EstimateStatus.APPROVED)
                .subtotal(subtotal)
                .taxAmount(taxAmount)
                .total(total)
                .version(1)
                .createdById(TEST_USER_ID.toString())
                .build();

        return estimateRepository.save(estimate);
    }

    private Workorder createTestWorkorder(UUID estimateId) {
        Workorder workorder = Workorder.builder()
                .shopId(UUID.fromString("550e8400-e29b-41d4-a716-446655440050"))
                .customerId(UUID.fromString("550e8400-e29b-41d4-a716-446655440051"))
                .vehicleId(UUID.fromString("550e8400-e29b-41d4-a716-446655440052"))
                .estimate(estimateRepository.getReferenceById(estimateId))
                .status(WorkorderStatus.DRAFT)
                .build();

        return workorderRepository.save(workorder);
    }

    private Workorder createApprovedWorkorder(UUID estimateId) {
        Workorder workorder = createTestWorkorder(estimateId);
        workorder.setStatus(WorkorderStatus.APPROVED);
        return workorderRepository.save(workorder);
    }
}
