package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.replenishment.CreateReplenishmentPolicyRequest;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentPolicyResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentTaskResponse;
import com.positivity.inventory.internal.entity.ReplenishmentPolicy;
import com.positivity.inventory.internal.entity.ReplenishmentTask;
import com.positivity.inventory.internal.enums.ReplenishmentStatus;
import com.positivity.inventory.internal.repository.ReplenishmentPolicyRepository;
import com.positivity.inventory.internal.repository.ReplenishmentTaskRepository;
import com.positivity.inventory.internal.service.ReplenishmentServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReplenishmentServiceImplTest {

        @InjectMocks
        private ReplenishmentServiceImpl replenishmentService;

        @Mock
        private ReplenishmentTaskRepository replenishmentTaskRepository;

        @Mock
        private ReplenishmentPolicyRepository replenishmentPolicyRepository;

        @Test
        void getReplenishmentTasks_shouldReturnMappedTasks() {
                // Given
                UUID taskId1 = UUID.randomUUID();
                UUID taskId2 = UUID.randomUUID();
                Instant now = Instant.now();

                ReplenishmentTask pendingTask = ReplenishmentTask.builder()
                                .taskId(taskId1)
                                .itemSKU("SKU123")
                                .quantity(10)
                                .sourceLocationId("SRC01")
                                .destinationLocationId("DST01")
                                .status(ReplenishmentStatus.PENDING)
                                .createdAt(now)
                                .build();

                ReplenishmentTask inProgressTask = ReplenishmentTask.builder()
                                .taskId(taskId2)
                                .itemSKU("SKU456")
                                .quantity(5)
                                .sourceLocationId("SRC02")
                                .destinationLocationId("DST02")
                                .status(ReplenishmentStatus.IN_PROGRESS)
                                .createdAt(now)
                                .build();

                when(replenishmentTaskRepository.findByStatusIn(
                                List.of(ReplenishmentStatus.PENDING, ReplenishmentStatus.IN_PROGRESS)))
                                .thenReturn(List.of(pendingTask, inProgressTask));

                // When
                List<ReplenishmentTaskResponse> responses = replenishmentService.getReplenishmentTasks();

                // Then
                assertEquals(2, responses.size());

                ReplenishmentTaskResponse response1 = responses.stream()
                                .filter(r -> r.getTaskId().equals(taskId1.toString()))
                                .findFirst().get();
                assertEquals("SKU123", response1.getItemSKU());
                assertEquals(10, response1.getQuantity());
                assertEquals("PENDING", response1.getStatus());

                ReplenishmentTaskResponse response2 = responses.stream()
                                .filter(r -> r.getTaskId().equals(taskId2.toString()))
                                .findFirst().get();
                assertEquals("SKU456", response2.getItemSKU());
                assertEquals(5, response2.getQuantity());
                assertEquals("IN_PROGRESS", response2.getStatus());
        }

        @Test
        void getReplenishmentTasks_shouldReturnEmptyListWhenNoTasks() {
                // Given
                when(replenishmentTaskRepository.findByStatusIn(
                                List.of(ReplenishmentStatus.PENDING, ReplenishmentStatus.IN_PROGRESS)))
                                .thenReturn(Collections.emptyList());

                // When
                List<ReplenishmentTaskResponse> responses = replenishmentService.getReplenishmentTasks();

                // Then
                assertTrue(responses.isEmpty());
        }

        @Test
        void getReplenishmentPolicies_shouldReturnMappedPolicies() {
                // Given
                UUID policyId = UUID.randomUUID();
                Instant now = Instant.now();
                ReplenishmentPolicy policy = ReplenishmentPolicy.builder()
                                .policyId(policyId)
                                .locationId("LOC01")
                                .itemSKU("SKU789")
                                .minimumQuantity(5)
                                .maximumQuantity(20)
                                .createdAt(now)
                                .build();

                when(replenishmentPolicyRepository.findAll()).thenReturn(List.of(policy));

                // When
                List<ReplenishmentPolicyResponse> responses = replenishmentService.getReplenishmentPolicies();

                // Then
                assertEquals(1, responses.size());
                ReplenishmentPolicyResponse response = responses.get(0);
                assertEquals(policyId.toString(), response.getPolicyId());
                assertEquals("LOC01", response.getLocationId());
                assertEquals("SKU789", response.getItemSKU());
                assertEquals(5, response.getMinimumQuantity());
                assertEquals(20, response.getMaximumQuantity());
        }

        @Test
        void createReplenishmentPolicy_shouldSaveAndReturnMappedPolicy() {
                // Given
                CreateReplenishmentPolicyRequest request = new CreateReplenishmentPolicyRequest("LOC02", "SKUABC", 10,
                                50);
                UUID policyId = UUID.randomUUID();
                Instant now = Instant.now();

                ReplenishmentPolicy savedPolicy = ReplenishmentPolicy.builder()
                                .policyId(policyId)
                                .locationId(request.getLocationId())
                                .itemSKU(request.getItemSKU())
                                .minimumQuantity(request.getMinimumQuantity())
                                .maximumQuantity(request.getMaximumQuantity())
                                .createdAt(now)
                                .build();

                when(replenishmentPolicyRepository.save(any(ReplenishmentPolicy.class))).thenReturn(savedPolicy);

                // When
                ReplenishmentPolicyResponse response = replenishmentService.createReplenishmentPolicy(request);

                // Then
                assertNotNull(response);
                assertEquals(policyId.toString(), response.getPolicyId());
                assertEquals("LOC02", response.getLocationId());
                assertEquals("SKUABC", response.getItemSKU());
                assertEquals(10, response.getMinimumQuantity());
                assertEquals(50, response.getMaximumQuantity());
        }

        @Test
        void evaluatePickFaceForReplenishment_shouldReturnNoAction() {
                // Given
                when(replenishmentPolicyRepository.findByLocationId(any())).thenReturn(Collections.emptyList());

                // When
                ReplenishmentTaskResponse response = replenishmentService.evaluatePickFaceForReplenishment("PROD123",
                                "PICKFACE01");

                // Then
                assertNotNull(response);
                assertEquals("NO_ACTION", response.getStatus());
        }

        @Test
        void evaluatePickFaceForReplenishment_shouldReturnTaskAlreadyQueued_whenTaskAlreadyExists() {
                // Given - a policy exists for the location
                when(replenishmentPolicyRepository.findByLocationId(any()))
                                .thenReturn(List.of(ReplenishmentPolicy.builder().locationId("PICKFACE01").build()));
                // AND - a pending/in-progress replenishment task already exists for that
                // sku+location
                when(replenishmentTaskRepository.existsByItemSKUAndDestinationLocationIdAndStatusIn(
                                any(), any(), any()))
                                .thenReturn(true);

                // When
                ReplenishmentTaskResponse response = replenishmentService.evaluatePickFaceForReplenishment("PROD123",
                                "PICKFACE01");

                // Then
                assertNotNull(response);
                assertEquals("TASK_ALREADY_QUEUED", response.getStatus());
        }

        @Test
        void runBatchReplenishmentScan_shouldReturnEmptyList() {
                // When
                List<ReplenishmentTaskResponse> responses = replenishmentService.runBatchReplenishmentScan();

                // Then
                assertTrue(responses.isEmpty());
        }
}
