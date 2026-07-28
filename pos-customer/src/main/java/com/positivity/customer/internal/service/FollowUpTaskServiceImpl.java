package com.positivity.customer.internal.service;

import com.positivity.customer.internal.dto.CloseFollowUpTaskRequest;
import com.positivity.customer.internal.dto.CreateFollowUpTaskRequest;
import com.positivity.customer.internal.dto.FollowUpTaskResponse;
import com.positivity.customer.internal.dto.PagedResponse;
import com.positivity.customer.internal.entity.FollowUpTask;
import com.positivity.customer.internal.enums.FollowUpStatus;
import com.positivity.customer.internal.enums.FollowUpType;
import com.positivity.customer.internal.exception.CrmResourceNotFoundException;
import com.positivity.customer.internal.exception.CrmUnprocessableEntityException;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.FollowUpTaskRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.service.FollowUpTaskService;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class FollowUpTaskServiceImpl implements FollowUpTaskService {

    private static final String TASK = "Follow-up task";
    private static final int MAX_PAGE_SIZE = 200;

    private final Clock clock;
    private final FollowUpTaskRepository taskRepository;
    private final CommercialPartyRepository commercialPartyRepository;
    private final PersonPartyRepository personPartyRepository;

    @Override
    @Transactional
    public @NonNull FollowUpTaskResponse create(@NonNull UUID partyId, @NonNull CreateFollowUpTaskRequest request) {
        assertPartyExists(partyId);
        FollowUpTask task = FollowUpTask.builder()
                .partyId(partyId)
                .vehicleId(request.getVehicleId())
                .type(request.getType())
                .dueDate(request.getDueDate())
                .assignedTo(request.getAssignedTo())
                .status(FollowUpStatus.OPEN)
                .reason(request.getReason())
                .sourceWorkorderId(request.getSourceWorkorderId())
                .notes(request.getNotes())
                .createdBy(SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                .build();
        return toResponse(taskRepository.save(task));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull FollowUpTaskResponse get(@NonNull UUID taskId) {
        return toResponse(requireTask(taskId));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull PagedResponse<FollowUpTaskResponse> listForParty(
            @NonNull UUID partyId, @Nullable FollowUpStatus status, int page, int size) {
        Pageable pageable = pageable(page, size);
        return PagedResponse.from(
                status == null
                        ? taskRepository.findByPartyIdOrderByDueDateAscCreatedAtAsc(partyId, pageable)
                        : taskRepository.findByPartyIdAndStatusOrderByDueDateAscCreatedAtAsc(partyId, status, pageable),
                FollowUpTaskServiceImpl::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull PagedResponse<FollowUpTaskResponse> queue(
            @Nullable FollowUpStatus status,
            @Nullable String assignedTo,
            @Nullable FollowUpType type,
            int page,
            int size) {
        return PagedResponse.from(
                taskRepository.findQueue(status, assignedTo, type, pageable(page, size)),
                FollowUpTaskServiceImpl::toResponse);
    }

    @Override
    @Transactional
    public @NonNull FollowUpTaskResponse assign(@NonNull UUID taskId, @Nullable String assignedTo) {
        FollowUpTask task = requireOpenTask(taskId);
        task.setAssignedTo(assignedTo);
        return toResponse(taskRepository.save(task));
    }

    @Override
    @Transactional
    public @NonNull FollowUpTaskResponse complete(@NonNull UUID taskId, @NonNull CloseFollowUpTaskRequest request) {
        return close(taskId, FollowUpStatus.DONE, request);
    }

    @Override
    @Transactional
    public @NonNull FollowUpTaskResponse dismiss(@NonNull UUID taskId, @NonNull CloseFollowUpTaskRequest request) {
        return close(taskId, FollowUpStatus.DISMISSED, request);
    }

    private FollowUpTaskResponse close(UUID taskId, FollowUpStatus status, CloseFollowUpTaskRequest request) {
        FollowUpTask task = requireOpenTask(taskId);
        task.setStatus(status);
        task.setOutcome(request.getOutcome());
        task.setBookedWorkorderId(request.getBookedWorkorderId());
        task.setBookedAppointmentId(request.getBookedAppointmentId());
        task.setClosedAt(Instant.now(clock));
        task.setClosedBy(SecurityContextHelper.getCurrentUsernameOrDefault("system"));
        FollowUpTask saved = taskRepository.save(task);
        log.info("Follow-up task {} closed as {} for party {}", taskId, status, task.getPartyId());
        return toResponse(saved);
    }

    private FollowUpTask requireOpenTask(UUID taskId) {
        FollowUpTask task = requireTask(taskId);
        if (task.getStatus().isClosed()) {
            // Reopening would discard the recorded outcome; raise a new task instead so the
            // history of what was already attempted survives.
            throw new CrmUnprocessableEntityException(
                    "Task is already " + task.getStatus() + " and cannot be modified; raise a new follow-up instead");
        }
        return task;
    }

    private FollowUpTask requireTask(UUID taskId) {
        return taskRepository.findById(taskId).orElseThrow(() -> new CrmResourceNotFoundException(TASK, taskId));
    }

    private void assertPartyExists(UUID partyId) {
        if (!commercialPartyRepository.existsById(partyId) && !personPartyRepository.existsById(partyId)) {
            throw new CrmResourceNotFoundException("Party", partyId);
        }
    }

    private static Pageable pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    private static FollowUpTaskResponse toResponse(FollowUpTask task) {
        return new FollowUpTaskResponse(
                task.getTaskId(),
                task.getPartyId(),
                task.getVehicleId(),
                task.getType().name(),
                task.getDueDate(),
                task.getAssignedTo(),
                task.getStatus().name(),
                task.getSourceWorkorderId(),
                task.getReason(),
                task.getOutcome(),
                task.getNotes(),
                task.getBookedWorkorderId(),
                task.getBookedAppointmentId(),
                task.getClosedAt(),
                task.getClosedBy(),
                task.getCreatedAt());
    }
}
