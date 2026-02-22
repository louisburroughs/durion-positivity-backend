package com.positivity.inventory.service;

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
import com.positivity.inventory.internal.service.CycleCountServiceImpl;
import com.positivity.inventory.internal.repository.CountEntryRepository;
import com.positivity.inventory.internal.repository.CycleCountTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies cycle count service behavior for recount rules and task transitions.
 *
 * Issue: CAP-221
 */
@ExtendWith(MockitoExtension.class)
class CycleCountServiceImplTest {

        @Mock
        private CycleCountTaskRepository taskRepository;

        @Mock
        private CountEntryRepository countEntryRepository;

        private CycleCountServiceImpl service;

        @BeforeEach
        void setUp() {
                service = new CycleCountServiceImpl(taskRepository, countEntryRepository);
        }

        @Test
        void submitCount_persistsEntryAndTransitionsTaskToPendingReview() {
                UUID taskId = UUID.randomUUID();
                CycleCountTask task = assignedTask(taskId, "auditor-1", 10, 0, null);
                SubmitCountRequest request = SubmitCountRequest.builder()
                                .taskId(taskId)
                                .auditorId("auditor-1")
                                .actualQuantity(8)
                                .build();

                when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
                when(countEntryRepository.save(any(CountEntry.class))).thenAnswer(invocation -> {
                        CountEntry entry = invocation.getArgument(0);
                        entry.setCountEntryId(UUID.randomUUID());
                        entry.setCountedAt(LocalDateTime.now());
                        return entry;
                });
                when(taskRepository.save(any(CycleCountTask.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

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
                                .taskId(UUID.randomUUID())
                                .auditorId("auditor-1")
                                .actualQuantity(-1)
                                .build();

                assertThatThrownBy(() -> service.submitCount(request))
                                .isInstanceOf(InvalidCountQuantityException.class);

                verify(taskRepository, never()).findById(any(UUID.class));
        }

        @Test
        void submitCount_rejectsTaskWhenStatusIsNotAssigned() {
                UUID taskId = UUID.randomUUID();
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
                UUID taskId = UUID.randomUUID();
                CycleCountTask task = assignedTask(taskId, "auditor-1", 10, 3, UUID.randomUUID());

                SubmitRecountRequest request = SubmitRecountRequest.builder()
                                .taskId(taskId)
                                .auditorId("auditor-1")
                                .actualQuantity(9)
                                .permission("TRIGGER_RECOUNT_SELF")
                                .build();

                when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
                when(taskRepository.save(any(CycleCountTask.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                assertThatThrownBy(() -> service.submitRecount(request))
                                .isInstanceOf(RecountLimitExceededException.class);

                assertThat(task.getStatus()).isEqualTo(TaskStatus.REQUIRES_INVESTIGATION);
                verify(taskRepository).save(task);
        }

        @Test
        void submitRecount_rejectsWhenAuditorIsNotOriginalAssignee() {
                UUID taskId = UUID.randomUUID();
                UUID previousEntryId = UUID.randomUUID();
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
                UUID taskId = UUID.randomUUID();
                UUID previousEntryId = UUID.randomUUID();
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
                UUID taskId = UUID.randomUUID();
                UUID previousEntryId = UUID.randomUUID();
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
                        entry.setCountEntryId(UUID.randomUUID());
                        entry.setCountedAt(LocalDateTime.now());
                        return entry;
                });
                when(taskRepository.save(any(CycleCountTask.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                CountResponse response = service.submitRecount(request);

                assertThat(response.isLimitExceeded()).isTrue();
                assertThat(response.getMessage()).contains("Maximum recount limit reached");
                assertThat(response.getRecountSequenceNumber()).isEqualTo(2);
                assertThat(task.getCountEntriesCount()).isEqualTo(3);
        }

        @Test
        void getTaskAndHistoryAndTasksByAuditor_mapResponsesFromRepositories() {
                UUID taskId = UUID.randomUUID();
                UUID entryId = UUID.randomUUID();
                CycleCountTask task = assignedTask(taskId, "auditor-1", 10, 1, entryId);
                task.setBinLocation("BIN-A");
                task.setItemSku("SKU-1");
                task.setItemDescription("Test Item");
                task.setCreatedAt(LocalDateTime.now().minusHours(1));
                task.setUpdatedAt(LocalDateTime.now());

                CountEntry entry = previousEntry(taskId, entryId, 0);

                when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
                when(countEntryRepository.findByCycleCountTaskIdOrderByRecountSequenceNumberAsc(taskId))
                                .thenReturn(List.of(entry));
                when(taskRepository.findByAuditorId("auditor-1")).thenReturn(List.of(task));

                CycleCountTaskResponse taskResponse = service.getTask(taskId);
                List<CountEntryResponse> history = service.getCountHistory(taskId);
                List<CycleCountTaskResponse> tasks = service.getTasksByAuditor("auditor-1");

                assertThat(taskResponse.getTaskId()).isEqualTo(taskId);
                assertThat(history).hasSize(1);
                assertThat(history.getFirst().isRecount()).isFalse();
                assertThat(tasks).hasSize(1);
                assertThat(tasks.getFirst().getAuditorId()).isEqualTo("auditor-1");
        }

        private CycleCountTask assignedTask(UUID taskId, String auditorId, int expectedQuantity, int countEntriesCount,
                        UUID latestEntryId) {
                return CycleCountTask.builder()
                                .taskId(taskId)
                                .auditorId(auditorId)
                                .expectedQuantity(expectedQuantity)
                                .countEntriesCount(countEntriesCount)
                                .latestCountEntryId(latestEntryId)
                                .status(TaskStatus.ASSIGNED)
                                .build();
        }

        private CountEntry previousEntry(UUID taskId, UUID countEntryId, int recountSequence) {
                return CountEntry.builder()
                                .countEntryId(countEntryId)
                                .cycleCountTaskId(taskId)
                                .auditorId("auditor-1")
                                .actualQuantity(10)
                                .expectedQuantity(10)
                                .variance(0)
                                .recountSequenceNumber(recountSequence)
                                .countedAt(LocalDateTime.now().minusMinutes(5))
                                .build();
        }
}
