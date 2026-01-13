package com.positivity.workorder.service;

import com.positivity.workorder.entity.AuditEvent;
import com.positivity.workorder.entity.Estimate;
import com.positivity.workorder.entity.WorkOrder;
import com.positivity.workorder.entity.WorkOrderStatus;
import com.positivity.workorder.repository.AuditEventRepository;
import com.positivity.workorder.repository.EstimateRepository;
import com.positivity.workorder.repository.WorkOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Estimate Revision and Approval Invalidation workflow.
 * 
 * These tests verify that when an Estimate's financial total changes,
 * associated WorkOrders in APPROVED status are automatically transitioned
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
    private WorkOrderService workOrderService;
    
    @Autowired
    private EstimateRepository estimateRepository;
    
    @Autowired
    private WorkOrderRepository workOrderRepository;
    
    @Autowired
    private AuditEventRepository auditEventRepository;
    
    private Estimate testEstimate;
    private WorkOrder testWorkOrder;
    private static final Long TEST_USER_ID = 999L;
    
    @BeforeEach
    void setUp() {
        // Clean up any existing test data
        auditEventRepository.deleteAll();
        workOrderRepository.deleteAll();
        estimateRepository.deleteAll();
    }
    
    /**
     * Test Scenario 1: Estimate total increases due to adding a line item
     * 
     * Given: A WorkOrder in APPROVED status linked to an Estimate totaling $500.00
     * When: The estimate total is increased to $650.00
     * Then: WorkOrder status must automatically transition to AWAITING_APPROVAL
     * And: An audit event must be created capturing the change
     */
    @Test
    void testApprovalInvalidatedWhenEstimateTotalIncreases() {
        // Given: Create estimate with initial total
        testEstimate = createTestEstimate(
            new BigDecimal("475.00"),  // subtotal
            new BigDecimal("25.00"),   // tax
            new BigDecimal("500.00")   // total
        );
        
        // Create work order in APPROVED status
        testWorkOrder = createApprovedWorkOrder(testEstimate.getId());
        
        // When: Update estimate with higher total (simulating adding a line item)
        estimateService.updateEstimateFinancials(
            testEstimate.getId(),
            new BigDecimal("600.00"),  // new subtotal
            new BigDecimal("50.00"),   // new tax
            new BigDecimal("650.00"),  // new total
            TEST_USER_ID
        );
        
        // Then: WorkOrder status should be AWAITING_APPROVAL
        WorkOrder updatedWorkOrder = workOrderRepository.findById(testWorkOrder.getId())
            .orElseThrow();
        assertThat(updatedWorkOrder.getStatus())
            .as("WorkOrder should transition to AWAITING_APPROVAL after estimate increase")
            .isEqualTo(WorkOrderStatus.AWAITING_APPROVAL);
        
        // And: Audit event should be created
        List<AuditEvent> auditEvents = auditEventRepository.findAll();
        assertThat(auditEvents)
            .as("Should have created an audit event")
            .hasSize(1);
        
        AuditEvent audit = auditEvents.get(0);
        assertThat(audit.getEntityType()).isEqualTo("WorkOrder");
        assertThat(audit.getEntityId()).isEqualTo(testWorkOrder.getId());
        assertThat(audit.getEventType()).isEqualTo("approval.invalidated");
        assertThat(audit.getUserId()).isEqualTo(TEST_USER_ID);
        assertThat(audit.getDetails())
            .contains("500.00")  // old total
            .contains("650.00")  // new total
            .contains("Previous status: APPROVED")
            .contains("New status: AWAITING_APPROVAL");
    }
    
    /**
     * Test Scenario 2: Estimate total decreases due to changing a part price
     * 
     * Given: A WorkOrder in APPROVED status linked to an Estimate totaling $500.00
     * When: The estimate total is decreased to $450.00
     * Then: WorkOrder status must still transition to AWAITING_APPROVAL
     * (Any change requires re-approval, regardless of direction)
     */
    @Test
    void testApprovalInvalidatedWhenEstimateTotalDecreases() {
        // Given
        testEstimate = createTestEstimate(
            new BigDecimal("475.00"),
            new BigDecimal("25.00"),
            new BigDecimal("500.00")
        );
        testWorkOrder = createApprovedWorkOrder(testEstimate.getId());
        
        // When: Decrease total
        estimateService.updateEstimateFinancials(
            testEstimate.getId(),
            new BigDecimal("425.00"),  // lower subtotal
            new BigDecimal("25.00"),
            new BigDecimal("450.00"),  // lower total
            TEST_USER_ID
        );
        
        // Then
        WorkOrder updatedWorkOrder = workOrderRepository.findById(testWorkOrder.getId())
            .orElseThrow();
        assertThat(updatedWorkOrder.getStatus())
            .as("WorkOrder should transition to AWAITING_APPROVAL even when estimate decreases")
            .isEqualTo(WorkOrderStatus.AWAITING_APPROVAL);
    }
    
    /**
     * Test Scenario 3: Non-financial change to estimate
     * 
     * Given: A WorkOrder in APPROVED status
     * When: The estimate is updated but total remains unchanged
     * Then: WorkOrder status must remain APPROVED
     */
    @Test
    void testApprovalNotInvalidatedWhenEstimateTotalUnchanged() {
        // Given
        testEstimate = createTestEstimate(
            new BigDecimal("475.00"),
            new BigDecimal("25.00"),
            new BigDecimal("500.00")
        );
        testWorkOrder = createApprovedWorkOrder(testEstimate.getId());
        
        // When: Update with different breakdown but same total
        estimateService.updateEstimateFinancials(
            testEstimate.getId(),
            new BigDecimal("480.00"),  // changed subtotal
            new BigDecimal("20.00"),   // changed tax
            new BigDecimal("500.00"),  // same total
            TEST_USER_ID
        );
        
        // Then: Status should remain APPROVED
        WorkOrder updatedWorkOrder = workOrderRepository.findById(testWorkOrder.getId())
            .orElseThrow();
        assertThat(updatedWorkOrder.getStatus())
            .as("WorkOrder should remain APPROVED when estimate total is unchanged")
            .isEqualTo(WorkOrderStatus.APPROVED);
        
        // And: No audit events should be created
        List<AuditEvent> auditEvents = auditEventRepository.findAll();
        assertThat(auditEvents)
            .as("Should not create audit event when total unchanged")
            .isEmpty();
    }
    
    /**
     * Test Scenario 4: Approval invalidation only affects APPROVED WorkOrders
     * 
     * Given: A WorkOrder in WORK_IN_PROGRESS status
     * When: The linked estimate total changes
     * Then: WorkOrder status should remain WORK_IN_PROGRESS (not invalidated)
     */
    @Test
    void testApprovalInvalidationOnlyAffectsApprovedWorkOrders() {
        // Given: WorkOrder in different status
        testEstimate = createTestEstimate(
            new BigDecimal("475.00"),
            new BigDecimal("25.00"),
            new BigDecimal("500.00")
        );
        
        testWorkOrder = createTestWorkOrder(testEstimate.getId());
        testWorkOrder.setStatus(WorkOrderStatus.WORK_IN_PROGRESS);
        testWorkOrder = workOrderRepository.save(testWorkOrder);
        
        // When: Update estimate total
        estimateService.updateEstimateFinancials(
            testEstimate.getId(),
            new BigDecimal("600.00"),
            new BigDecimal("50.00"),
            new BigDecimal("650.00"),
            TEST_USER_ID
        );
        
        // Then: Status should remain unchanged
        WorkOrder updatedWorkOrder = workOrderRepository.findById(testWorkOrder.getId())
            .orElseThrow();
        assertThat(updatedWorkOrder.getStatus())
            .as("WorkOrder not in APPROVED status should not be affected")
            .isEqualTo(WorkOrderStatus.WORK_IN_PROGRESS);
        
        // And: No audit events for approval invalidation
        List<AuditEvent> auditEvents = auditEventRepository.findAll();
        assertThat(auditEvents)
            .as("Should not create audit event for non-approved work orders")
            .isEmpty();
    }
    
    /**
     * Test: Multiple WorkOrders linked to same estimate
     * 
     * Given: Two WorkOrders both in APPROVED status, linked to same Estimate
     * When: The estimate total changes
     * Then: Both WorkOrders should transition to AWAITING_APPROVAL
     */
    @Test
    void testMultipleWorkOrdersInvalidatedForSameEstimate() {
        // Given: Two work orders for same estimate
        testEstimate = createTestEstimate(
            new BigDecimal("475.00"),
            new BigDecimal("25.00"),
            new BigDecimal("500.00")
        );
        
        WorkOrder workOrder1 = createApprovedWorkOrder(testEstimate.getId());
        WorkOrder workOrder2 = createApprovedWorkOrder(testEstimate.getId());
        
        // When: Update estimate
        estimateService.updateEstimateFinancials(
            testEstimate.getId(),
            new BigDecimal("600.00"),
            new BigDecimal("50.00"),
            new BigDecimal("650.00"),
            TEST_USER_ID
        );
        
        // Then: Both should be invalidated
        WorkOrder updated1 = workOrderRepository.findById(workOrder1.getId()).orElseThrow();
        WorkOrder updated2 = workOrderRepository.findById(workOrder2.getId()).orElseThrow();
        
        assertThat(updated1.getStatus()).isEqualTo(WorkOrderStatus.AWAITING_APPROVAL);
        assertThat(updated2.getStatus()).isEqualTo(WorkOrderStatus.AWAITING_APPROVAL);
        
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
        testEstimate = createTestEstimate(
            new BigDecimal("475.00"),
            new BigDecimal("25.00"),
            new BigDecimal("500.00")
        );
        
        assertThat(testEstimate.getVersion())
            .as("Initial estimate version should be 1")
            .isEqualTo(1);
        
        // When: Update with different total
        estimateService.updateEstimateFinancials(
            testEstimate.getId(),
            new BigDecimal("600.00"),
            new BigDecimal("50.00"),
            new BigDecimal("650.00"),
            TEST_USER_ID
        );
        
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
        testEstimate = createTestEstimate(
            new BigDecimal("475.00"),
            new BigDecimal("25.00"),
            new BigDecimal("500.00")
        );
        
        // When: Update with same total
        estimateService.updateEstimateFinancials(
            testEstimate.getId(),
            new BigDecimal("480.00"),
            new BigDecimal("20.00"),
            new BigDecimal("500.00"),  // same total
            TEST_USER_ID
        );
        
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
                .locationId(1L)
                .customerId(100L)
                .vehicleId(200L)
                .status(Estimate.EstimateStatus.APPROVED)
                .subtotal(subtotal)
                .taxAmount(taxAmount)
                .total(total)
                .version(1)
                .createdById(TEST_USER_ID)
                .build();
        
        return estimateRepository.save(estimate);
    }
    
    private WorkOrder createTestWorkOrder(Long estimateId) {
        WorkOrder workOrder = WorkOrder.builder()
                .shopId(1L)
                .customerId(100L)
                .vehicleId(200L)
                .estimateId(estimateId)
                .status(WorkOrderStatus.DRAFT)
                .build();
        
        return workOrderRepository.save(workOrder);
    }
    
    private WorkOrder createApprovedWorkOrder(Long estimateId) {
        WorkOrder workOrder = createTestWorkOrder(estimateId);
        workOrder.setStatus(WorkOrderStatus.APPROVED);
        return workOrderRepository.save(workOrder);
    }
}
