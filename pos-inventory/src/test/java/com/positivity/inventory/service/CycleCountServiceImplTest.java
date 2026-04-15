package com.positivity.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.cyclecount.CountEntryResponse;
import com.positivity.inventory.internal.dto.cyclecount.CountResponse;
import com.positivity.inventory.internal.dto.cyclecount.CycleCountTaskResponse;
import com.positivity.inventory.internal.dto.cyclecount.SubmitCountRequest;
import com.positivity.inventory.internal.dto.cyclecount.SubmitRecountRequest;
import com.positivity.inventory.internal.entity.CountEntry;
import com.positivity.inventory.internal.entity.CycleCountTask;
import com.positivity.inventory.internal.enums.TaskStatus;
import com.positivity.inventory.internal.exception.InsufficientPermissionException;
import com.positivity.inventory.internal.exception.InvalidCountQuantityException;
import com.positivity.inventory.internal.exception.RecountLimitExceededException;
import com.positivity.inventory.internal.repository.CountEntryRepository;
import com.positivity.inventory.internal.repository.CycleCountTaskRepository;
import com.positivity.inventory.internal.service.CycleCountServiceImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies cycle count service behavior for recount rules and task transitions.
 *
 * Issue: CAP-221
 */
@ExtendWith(MockitoExtension.class)
class CycleCountServiceImplTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant TASK_CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TASK_UPDATED_AT = Instant.parse("2026-01-02T12:00:00Z");

    @Mock
    private CycleCountTaskRepository taskRepository;

    @Mock
    private CountEntryRepository countEntryRepository;

    private CycleCountServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CycleCountServiceImpl(taskRepository, countEntryRepository, Clock.systemDefaultZone());
    }

    @Test
    void submitCount_persistsEntryAndTransitionsTaskToPendingReview() {
        UUID taskId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CycleCountTask task = assignedTask(taskId, "auditor-1", 10, 0, null);
        SubmitCountRequest request = SubmitCountRequest.builder()
                .taskId(taskId)
                .auditorId("auditor-1")
                .actualQuantity(8)
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(countEntryRepository.save(any(CountEntry.class))).thenAnswer(invocation -> {
            CountEntry entry = invocation.getArgument(0);
            entry.setCountEntryId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
            entry.setCountedAt(Instant.now(TEST_CLOCK));
            return entry;
        });
        when(taskRepository.save(any(CycleCountTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CountResponse response = service.submitCount(request);

        assertThat(response.getTaskId()).isEqualTo(taskId);
        assertThat(response.getExpectedQuantity()).isEqualTo(10);
        assertThat(response.getActualQuantity()).isEqualTo(8);
        assertThat(response.getVariance()).isEqualTo(-2);
        assertThat(response.getTaskStatus()).isEqualTo(TaskStatus.COUNTED_PENDING_REVIEW);
        assertThat(response.isLimitExceeded()).isFalse();
        verify(countEntryRepository).save(any(CountEntry.class));
        verify(taskRepository).save(task);
    }

    @Test
    void submitCount_rejectsNegativeQuantity() {
        SubmitCountRequest request = SubmitCountRequest.builder()
                .taskId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .auditorId("auditor-1")
                .actualQuantity(-1)
                .build();

        assertThatThrownBy(() -> service.submitCount(request)).isInstanceOf(InvalidCountQuantityException.class);

        verify(taskRepository, never()).findById(any(UUID.class));
    }

    @Test
    void submitCount_rejectsTaskWhenStatusIsNotAssigned() {
        UUID taskId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CycleCountTask task = assignedTask(taskId, "auditor-1", 10, 0, null);
        task.setStatus(TaskStatus.COUNTED_PENDING_REVIEW);

        SubmitCountRequest request = SubmitCountRequest.builder()
                .taskId(taskId)
                .auditorId("auditor-1")
                .actualQuantity(5)
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.submitCount(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not in ASSIGNED status");
    }

    @Test
    void submitRecount_marksTaskForInvestigationWhenLimitExceeded() {
        UUID taskId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CycleCountTask task =
                assignedTask(taskId, "auditor-1", 10, 3, UUID.fromString("00000000-0000-0000-0000-000000000001"));

        SubmitRecountRequest request = SubmitRecountRequest.builder()
                .taskId(taskId)
                .auditorId("auditor-1")
                .actualQuantity(9)
                .permission("TRIGGER_RECOUNT_SELF")
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(CycleCountTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.submitRecount(request)).isInstanceOf(RecountLimitExceededException.class);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.REQUIRES_INVESTIGATION);
        verify(taskRepository).save(task);
    }

    @Test
    void submitRecount_rejectsWhenAuditorIsNotOriginalAssignee() {
        UUID taskId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID previousEntryId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CycleCountTask task = assignedTask(taskId, "auditor-1", 10, 1, previousEntryId);

        SubmitRecountRequest request = SubmitRecountRequest.builder()
                .taskId(taskId)
                .auditorId("auditor-2")
                .actualQuantity(11)
                .permission("TRIGGER_RECOUNT_SELF")
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.submitRecount(request))
                .isInstanceOf(InsufficientPermissionException.class)
                .hasMessageContaining("Only the original auditor");
    }

    @Test
    void submitRecount_rejectsInvalidPermissionValue() {
        UUID taskId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID previousEntryId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CycleCountTask task = assignedTask(taskId, "auditor-1", 10, 1, previousEntryId);

        SubmitRecountRequest request = SubmitRecountRequest.builder()
                .taskId(taskId)
                .auditorId("auditor-1")
                .actualQuantity(11)
                .permission("UNSUPPORTED")
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.submitRecount(request))
                .isInstanceOf(InsufficientPermissionException.class)
                .hasMessageContaining("Invalid permission");
    }

    @Test
    void submitRecount_withManagerPermissionReachesLimitAndReturnsLimitExceededFlag() {
        UUID taskId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID previousEntryId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CycleCountTask task = assignedTask(taskId, "auditor-1", 10, 2, previousEntryId);
        CountEntry previousEntry = previousEntry(taskId, previousEntryId, 1);

        SubmitRecountRequest request = SubmitRecountRequest.builder()
                .taskId(taskId)
                .auditorId("manager-1")
                .actualQuantity(12)
                .permission("TRIGGER_RECOUNT_ANY")
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(countEntryRepository.findById(previousEntryId)).thenReturn(Optional.of(previousEntry));
        when(countEntryRepository.save(any(CountEntry.class))).thenAnswer(invocation -> {
            CountEntry entry = invocation.getArgument(0);
            entry.setCountEntryId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
            entry.setCountedAt(Instant.now(TEST_CLOCK));
            return entry;
        });
        when(taskRepository.save(any(CycleCountTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CountResponse response = service.submitRecount(request);

        assertThat(response.isLimitExceeded()).isTrue();
        assertThat(response.getMessage()).contains("Maximum recount limit reached");
        assertThat(response.getRecountSequenceNumber()).isEqualTo(2);
        assertThat(task.getCountEntriesCount()).isEqualTo(3);
    }

    @Test
    void getTask_returnsTaskResponse() {
        UUID taskId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CycleCountTask task = assignedTask(taskId, "auditor-1", 10, 0, null);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        CycleCountTaskResponse response = service.getTask(taskId);

        assertThat(response.getTaskId()).isEqualTo(taskId);
        assertThat(response.getAuditorId()).isEqualTo("auditor-1");
        assertThat(response.getCreatedAt())
                .isNotNull()
                .isEqualTo(LocalDateTime.ofInstant(TASK_CREATED_AT, ZoneOffset.UTC));
        assertThat(response.getUpdatedAt())
                .isNotNull()
                .isEqualTo(LocalDateTime.ofInstant(TASK_UPDATED_AT, ZoneOffset.UTC));
    }

    @Test
    void getCountHistory_returnsListOfCountEntries() {
        UUID taskId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CountEntry entry1 = previousEntry(taskId, UUID.fromString("00000000-0000-0000-0000-000000000001"), 0);
        CountEntry entry2 = previousEntry(taskId, UUID.fromString("00000000-0000-0000-0000-000000000001"), 1);
        when(countEntryRepository.findByCycleCountTask_TaskIdOrderByRecountSequenceNumberAsc(taskId))
                .thenReturn(List.of(entry1, entry2));

        List<CountEntryResponse> history = service.getCountHistory(taskId);

        assertThat(history).hasSize(2);
        assertThat(history.get(0).getRecountSequenceNumber()).isZero();
        assertThat(history.get(1).getRecountSequenceNumber()).isEqualTo(1);
    }

    @Test
    void getTasksByAuditor_returnsListOfTasks() {
        String auditorId = "auditor-1";
        CycleCountTask task1 =
                assignedTask(UUID.fromString("00000000-0000-0000-0000-000000000001"), auditorId, 10, 0, null);
        CycleCountTask task2 =
                assignedTask(UUID.fromString("00000000-0000-0000-0000-000000000001"), auditorId, 20, 0, null);
        when(taskRepository.findByAuditorId(auditorId)).thenReturn(List.of(task1, task2));

        List<CycleCountTaskResponse> tasks = service.getTasksByAuditor(auditorId);

        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(0).getAuditorId()).isEqualTo(auditorId);
        assertThat(tasks.get(1).getAuditorId()).isEqualTo(auditorId);
    }

    @Test
    void submitRecount_succeedsOnSecondCountWithManagerPermission() {
        UUID taskId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID previousEntryId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CycleCountTask task = assignedTask(taskId, "auditor-1", 10, 1, previousEntryId);
        CountEntry previousEntry = previousEntry(taskId, previousEntryId, 0);

        SubmitRecountRequest request = SubmitRecountRequest.builder()
                .taskId(taskId)
                .auditorId("manager-1")
                .actualQuantity(11)
                .permission("TRIGGER_RECOUNT_ANY")
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(countEntryRepository.findById(previousEntryId)).thenReturn(Optional.of(previousEntry));
        when(countEntryRepository.save(any(CountEntry.class))).thenAnswer(invocation -> {
            CountEntry entry = invocation.getArgument(0);
            entry.setCountEntryId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
            entry.setCountedAt(Instant.now(TEST_CLOCK));
            return entry;
        });
        when(taskRepository.save(any(CycleCountTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CountResponse response = service.submitRecount(request);

        assertThat(response.getTaskStatus()).isEqualTo(TaskStatus.COUNTED_PENDING_REVIEW);
        assertThat(response.isLimitExceeded()).isFalse();
        assertThat(task.getCountEntriesCount()).isEqualTo(2);
    }

    private CycleCountTask assignedTask(
            UUID taskId, String auditorId, int expectedQuantity, int countEntriesCount, UUID latestEntryId) {
        return CycleCountTask.builder()
                .taskId(taskId)
                .auditorId(auditorId)
                .expectedQuantity(expectedQuantity)
                .countEntriesCount(countEntriesCount)
                .latestCountEntryId(latestEntryId)
                .status(TaskStatus.ASSIGNED)
                .createdAt(TASK_CREATED_AT)
                .updatedAt(TASK_UPDATED_AT)
                .build();
    }

    private CountEntry previousEntry(UUID taskId, UUID countEntryId, int recountSequence) {
        CycleCountTask task = CycleCountTask.builder().taskId(taskId).build();
        return CountEntry.builder()
                .countEntryId(countEntryId)
                .cycleCountTask(task)
                .auditorId("auditor-1")
                .actualQuantity(10)
                .expectedQuantity(10)
                .variance(0)
                .recountSequenceNumber(recountSequence)
                .countedAt(Instant.now(TEST_CLOCK).minus(5, java.time.temporal.ChronoUnit.MINUTES))
                .build();
    }
}
