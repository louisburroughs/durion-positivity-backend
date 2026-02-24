package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.replenishment.CreateReplenishmentPolicyRequest;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentPolicyResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentTaskResponse;
import com.positivity.inventory.internal.entity.ReplenishmentPolicy;
import com.positivity.inventory.internal.entity.ReplenishmentTask;
import com.positivity.inventory.internal.enums.ReplenishmentStatus;
import com.positivity.inventory.internal.repository.ReplenishmentPolicyRepository;
import com.positivity.inventory.internal.repository.ReplenishmentTaskRepository;
import com.positivity.inventory.service.ReplenishmentService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReplenishmentServiceImpl implements ReplenishmentService {

    private final ReplenishmentTaskRepository replenishmentTaskRepository;
    private final ReplenishmentPolicyRepository replenishmentPolicyRepository;

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<ReplenishmentTaskResponse> getReplenishmentTasks() {
        List<ReplenishmentTaskResponse> responses = new ArrayList<>();

        replenishmentTaskRepository.findByStatus(ReplenishmentStatus.PENDING)
                .stream()
                .map(this::toTaskResponse)
                .forEach(responses::add);

        replenishmentTaskRepository.findByStatus(ReplenishmentStatus.IN_PROGRESS)
                .stream()
                .map(this::toTaskResponse)
                .forEach(responses::add);

        return responses;
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<ReplenishmentPolicyResponse> getReplenishmentPolicies() {
        return replenishmentPolicyRepository.findAll()
                .stream()
                .map(this::toPolicyResponse)
                .toList();
    }

    @Override
    @Transactional
    public @NonNull ReplenishmentPolicyResponse createReplenishmentPolicy(
            @NonNull CreateReplenishmentPolicyRequest request) {
        ReplenishmentPolicy policy = ReplenishmentPolicy.builder()
                .locationId(request.getLocationId())
                .itemSKU(request.getItemSKU())
                .minimumQuantity(request.getMinimumQuantity())
                .maximumQuantity(request.getMaximumQuantity())
                .build();

        ReplenishmentPolicy saved = replenishmentPolicyRepository.save(policy);
        return toPolicyResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull ReplenishmentTaskResponse evaluatePickFaceForReplenishment(
            @NonNull String productId,
            @NonNull String pickFaceLocationId) {
        boolean policyExists = replenishmentPolicyRepository.findByLocationId(pickFaceLocationId)
                .stream()
                .findFirst()
                .isPresent();

        if (policyExists) {
            replenishmentTaskRepository.existsByItemSKUAndDestinationLocationIdAndStatusIn(
                    productId,
                    pickFaceLocationId,
                    List.of(ReplenishmentStatus.PENDING, ReplenishmentStatus.IN_PROGRESS));
        }

        return ReplenishmentTaskResponse.builder()
                .taskId(null)
                .status("NO_ACTION")
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<ReplenishmentTaskResponse> runBatchReplenishmentScan() {
        return List.of();
    }

    private ReplenishmentTaskResponse toTaskResponse(ReplenishmentTask task) {
        return ReplenishmentTaskResponse.builder()
                .taskId(task.getTaskId() != null ? task.getTaskId().toString() : null)
                .itemSKU(task.getItemSKU())
                .quantity(task.getQuantity() != null ? task.getQuantity() : 0)
                .sourceLocationId(task.getSourceLocationId())
                .destinationLocationId(task.getDestinationLocationId())
                .status(task.getStatus() != null ? task.getStatus().name() : null)
                .triggerType(task.getTriggerType() != null ? task.getTriggerType().name() : null)
                .decisionReason(task.getDecisionReason() != null ? task.getDecisionReason().name() : null)
                .sourcingReason(task.getSourcingReason() != null ? task.getSourcingReason().name() : null)
                .assignedTo(task.getAssignedTo())
                .createdAt(task.getCreatedAt() != null ? task.getCreatedAt().toString() : null)
                .build();
    }

    private ReplenishmentPolicyResponse toPolicyResponse(ReplenishmentPolicy policy) {
        return ReplenishmentPolicyResponse.builder()
                .policyId(policy.getPolicyId() != null ? policy.getPolicyId().toString() : null)
                .locationId(policy.getLocationId())
                .itemSKU(policy.getItemSKU())
                .minimumQuantity(policy.getMinimumQuantity() != null ? policy.getMinimumQuantity() : 0)
                .maximumQuantity(policy.getMaximumQuantity() != null ? policy.getMaximumQuantity() : 0)
                .createdAt(policy.getCreatedAt() != null ? policy.getCreatedAt().toString() : null)
                .build();
    }
}