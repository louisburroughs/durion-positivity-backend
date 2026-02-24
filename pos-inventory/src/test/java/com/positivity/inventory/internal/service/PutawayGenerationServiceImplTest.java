package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.putaway.GeneratePutawayTasksRequest;
import com.positivity.inventory.internal.dto.putaway.PutawayTaskResponse;
import com.positivity.inventory.internal.entity.PutawayRule;
import com.positivity.inventory.internal.entity.PutawayTask;
import com.positivity.inventory.internal.enums.PutawayTaskStatus;
import com.positivity.inventory.internal.exception.TaskNotFoundException;
import com.positivity.inventory.internal.repository.PutawayRuleRepository;
import com.positivity.inventory.internal.repository.PutawayTaskRepository;
import com.positivity.inventory.internal.service.PutawayGenerationServiceImpl;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PutawayGenerationServiceImplTest {

    @Mock
    private PutawayRuleRepository putawayRuleRepository;

    @Mock
    private PutawayTaskRepository putawayTaskRepository;

    private PutawayGenerationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PutawayGenerationServiceImpl(putawayRuleRepository, putawayTaskRepository);
    }

    @Test
    void generateTasksForReceipt_withRules_createsUnassignedTask() {
        GeneratePutawayTasksRequest request = new GeneratePutawayTasksRequest(UUID.randomUUID().toString());
        PutawayRule rule = new PutawayRule();
        rule.setDestinationLocationId("DEST-A");
        when(putawayRuleRepository.findAllByIsEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(rule));
        when(putawayTaskRepository.save(any(PutawayTask.class))).thenAnswer(inv -> inv.getArgument(0));

        List<PutawayTaskResponse> responses = service.generateTasksForReceipt(request);

        assertThat(responses).hasSize(1);
        PutawayTaskResponse response = responses.get(0);
        assertThat(response.getStatus()).isEqualTo(PutawayTaskStatus.UNASSIGNED.toString());
        assertThat(response.getSuggestedDestinationLocationId()).isEqualTo("DEST-A");
    }

    @Test
    void generateTasksForReceipt_noRules_createsUnassignedTaskWithDefaultLocation() {
        GeneratePutawayTasksRequest request = new GeneratePutawayTasksRequest(UUID.randomUUID().toString());
        when(putawayRuleRepository.findAllByIsEnabledTrueOrderByPriorityAsc()).thenReturn(Collections.emptyList());
        when(putawayTaskRepository.save(any(PutawayTask.class))).thenAnswer(inv -> inv.getArgument(0));

        List<PutawayTaskResponse> responses = service.generateTasksForReceipt(request);

        assertThat(responses).hasSize(1);
        PutawayTaskResponse response = responses.get(0);
        assertThat(response.getStatus()).isEqualTo(PutawayTaskStatus.UNASSIGNED.toString());
        assertThat(response.getSuggestedDestinationLocationId()).isEqualTo("DEFAULT-LOCATION");
    }

    @Test
    void getAvailableTasks_returnsUnassignedTasks() {
        PutawayTask task = new PutawayTask();
        task.setTaskId(UUID.randomUUID());
        task.setStatus(PutawayTaskStatus.UNASSIGNED);
        when(putawayTaskRepository.findByStatusIn(List.of(PutawayTaskStatus.UNASSIGNED))).thenReturn(List.of(task));

        List<PutawayTaskResponse> responses = service.getAvailableTasks();

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getTaskId()).isEqualTo(task.getTaskId().toString());
    }

    @Test
    void claimTask_validTask_updatesStatusAndAssignee() {
        UUID taskId = UUID.randomUUID();
        String userId = "user-123";
        PutawayTask task = new PutawayTask();
        task.setTaskId(taskId);
        task.setStatus(PutawayTaskStatus.UNASSIGNED);

        when(putawayTaskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(putawayTaskRepository.save(any(PutawayTask.class))).thenAnswer(inv -> inv.getArgument(0));

        PutawayTaskResponse response = service.claimTask(taskId.toString(), userId);

        assertThat(response.getTaskId()).isEqualTo(taskId.toString());
        assertThat(response.getStatus()).isEqualTo(PutawayTaskStatus.ASSIGNED.toString());
        assertThat(response.getAssigneeId()).isEqualTo(userId);
    }

    @Test
    void claimTask_invalidTask_throwsTaskNotFoundException() {
        UUID taskId = UUID.randomUUID();
        String userId = "user-123";
        when(putawayTaskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.claimTask(taskId.toString(), userId))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void getTasksByReceiptId_returnsFilteredList() {
        UUID receiptId = UUID.randomUUID();
        PutawayTask task = new PutawayTask();
        task.setTaskId(UUID.randomUUID());
        task.setSourceReceiptId(receiptId);

        when(putawayTaskRepository.findBySourceReceiptId(receiptId)).thenReturn(List.of(task));

        List<PutawayTaskResponse> responses = service.getTasksByReceiptId(receiptId.toString());

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getSourceReceiptId()).isEqualTo(receiptId.toString());
    }

    @Test
    void generateTasksForReceipt_withInvalidUuid_throwsIllegalArgumentException() {
        GeneratePutawayTasksRequest request = new GeneratePutawayTasksRequest("invalid-uuid");

        assertThatThrownBy(() -> service.generateTasksForReceipt(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceReceiptId must be a valid UUID");
    }

    @Test
    void generateTasksForReceipt_withNullUuid_throwsIllegalArgumentException() {
        GeneratePutawayTasksRequest request = new GeneratePutawayTasksRequest(null);

        assertThatThrownBy(() -> service.generateTasksForReceipt(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceReceiptId is required");
    }
}
