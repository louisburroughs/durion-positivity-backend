package com.positivity.workorder.service;

import com.positivity.workorder.entity.WorkOrder;
import com.positivity.workorder.repository.WorkOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.beans.factory.annotation.Value;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkOrderService {
    private final WorkOrderRepository workOrderRepository;
    private final RestTemplate restTemplate;

    @Value("${customer.service.url:http://localhost:8080/api/customers}")
    private String customerServiceUrl;
    @Value("${customer.approval.service.url:http://localhost:8080/api/approvals}")
    private String customerApprovalServiceUrl;

    public List<WorkOrder> getAllWorkOrders() {
        return workOrderRepository.findAll();
    }

    public Optional<WorkOrder> getWorkOrderById(Long id) {
        return workOrderRepository.findById(id);
    }

    @Transactional
    public WorkOrder createWorkOrder(WorkOrder workOrder) {
        // Check customer requirements from pos-customer
        if (!checkCustomerRequirements(workOrder.getCustomerId())) {
            throw new IllegalArgumentException("Customer requirements not met");
        }
        // Check customer approval from pos-customer-approval
        if (!checkCustomerApproval(workOrder.getApprovalId())) {
            throw new IllegalArgumentException("Customer approval not found or not valid");
        }
        return workOrderRepository.save(workOrder);
    }

    public void deleteWorkOrder(Long id) {
        workOrderRepository.deleteById(id);
    }

    private boolean checkCustomerRequirements(Long customerId) {
        try {
            Boolean result = restTemplate.getForObject(customerServiceUrl + "/" + customerId + "/requirements-met", Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("Failed to check customer requirements", e);
            return false;
        }
    }

    private boolean checkCustomerApproval(Long approvalId) {
        try {
            Boolean result = restTemplate.getForObject(customerApprovalServiceUrl + "/" + approvalId + "/is-approved", Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("Failed to check customer approval", e);
            return false;
        }
    }
}

