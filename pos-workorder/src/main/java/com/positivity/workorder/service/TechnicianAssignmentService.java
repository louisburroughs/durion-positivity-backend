package com.positivity.workorder.service;

import com.positivity.workorder.internal.entity.TechnicianAssignment;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderStatus;
import com.positivity.workorder.internal.repository.TechnicianAssignmentRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing technician assignments to workorders.
 * 
 * <p>Business rules:
 * <ul>
 *   <li>Assignment is allowed only from APPROVED, ASSIGNED, or WORK_IN_PROGRESS status</li>
 *   <li>Initial assignment transitions workorder to ASSIGNED status</li>
 *   <li>Reassignment marks previous assignment as not current</li>
 *   <li>Assignment history is append-only and ordered newest-first</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TechnicianAssignmentService {
    
    private final TechnicianAssignmentRepository assignmentRepository;
    private final WorkorderRepository workorderRepository;
    private final WorkorderStateMachine stateMachine;
    
    private static final String REASSIGNMENT_REASON = "Reassigned to different technician";
    private static final String WORKORDER_NOT_FOUND = "Workorder not found: ";
    
    /**
     * Assign a technician to a workorder.
     * 
     * <p>If the workorder is in APPROVED status, it will be transitioned to ASSIGNED.
     * If already assigned, this effectively performs a reassignment.
     * 
     * @param workorderId the workorder ID
     * @param technicianId the technician ID to assign
     * @param assignedBy the user ID performing the assignment
     * @param notes optional notes for the assignment
     * @return the created assignment record
     * @throws NoSuchElementException if workorder not found
     * @throws IllegalStateException if workorder status doesn't allow assignment
     */
    @Transactional
    @NonNull
    public TechnicianAssignment assignTechnician(
            @NonNull UUID workorderId,
            @NonNull UUID technicianId,
            @NonNull UUID assignedBy,
            @Nullable String notes) {
        
        Workorder workorder = workorderRepository.findById(workorderId)
                .orElseThrow(() -> new NoSuchElementException(WORKORDER_NOT_FOUND + workorderId));
        
        // Validate status
        validateAssignmentAllowed(workorder);
        
        // Mark any existing assignment as not current
        Optional<TechnicianAssignment> existingAssignment = assignmentRepository
                .findByWorkorderIdAndCurrentTrue(workorderId);
        
        if (existingAssignment.isPresent()) {
            log.debug("Found existing assignment for workorder {}, marking as not current", workorderId);
            TechnicianAssignment existing = existingAssignment.get();
            existing.markAsNotCurrent(LocalDateTime.now(), REASSIGNMENT_REASON);
            assignmentRepository.save(existing);
        }
        
        // Create new assignment
        TechnicianAssignment assignment = TechnicianAssignment.builder()
                .workorderId(workorderId)
                .technicianId(technicianId)
                .assignedBy(assignedBy)
                .assignedAt(LocalDateTime.now())
                .notes(notes)
                .current(true)
                .build();
        
        TechnicianAssignment saved = assignmentRepository.save(assignment);
        log.info("Assigned technician {} to workorder {} by user {}", technicianId, workorderId, assignedBy);
        
        // Transition workorder to ASSIGNED status if currently APPROVED
        if (workorder.getStatus() == WorkorderStatus.APPROVED) {
            stateMachine.transitionWorkorder(workorderId, WorkorderStatus.ASSIGNED, assignedBy, 
                    "Technician assigned");
            log.debug("Transitioned workorder {} to ASSIGNED status", workorderId);
        }
        
        return saved;
    }
    
    /**
     * Reassign a workorder to a different technician.
     * 
     * <p>This is semantically similar to assignTechnician but explicitly indicates
     * that the workorder had a previous assignment. The reason is captured in the
     * new assignment record.
     * 
     * @param workorderId the workorder ID
     * @param newTechnicianId the new technician ID
     * @param reassignedBy the user ID performing the reassignment
     * @param reason the reason for reassignment
     * @param notes optional additional notes
     * @return the new assignment record
     * @throws NoSuchElementException if workorder not found or no current assignment exists
     * @throws IllegalStateException if workorder status doesn't allow reassignment
     */
    @Transactional
    @NonNull
    public TechnicianAssignment reassignTechnician(
            @NonNull UUID workorderId,
            @NonNull UUID newTechnicianId,
            @NonNull UUID reassignedBy,
            @Nullable String reason,
            @Nullable String notes) {
        
        Workorder workorder = workorderRepository.findById(workorderId)
                .orElseThrow(() -> new NoSuchElementException(WORKORDER_NOT_FOUND + workorderId));
        
        // Validate status
        validateAssignmentAllowed(workorder);
        
        // Ensure there's a current assignment to reassign from
        TechnicianAssignment currentAssignment = assignmentRepository
                .findByWorkorderIdAndCurrentTrue(workorderId)
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot reassign: no current technician assignment for workorder " + workorderId));
        
        UUID previousTechnicianId = currentAssignment.getTechnicianId();
        log.debug("Reassigning workorder {} from technician {} to {}", 
                workorderId, previousTechnicianId, newTechnicianId);
        
        // Mark current assignment as not current
        currentAssignment.markAsNotCurrent(LocalDateTime.now(), reason);
        assignmentRepository.save(currentAssignment);
        
        // Create new assignment
        TechnicianAssignment newAssignment = TechnicianAssignment.builder()
                .workorderId(workorderId)
                .technicianId(newTechnicianId)
                .assignedBy(reassignedBy)
                .assignedAt(LocalDateTime.now())
                .notes(notes)
                .reassignmentReason(reason)
                .current(true)
                .build();
        
        TechnicianAssignment saved = assignmentRepository.save(newAssignment);
        log.info("Reassigned workorder {} from technician {} to {} by user {}", 
                workorderId, previousTechnicianId, newTechnicianId, reassignedBy);
        
        return saved;
    }
    
    /**
     * Get the current assignment for a workorder.
     * 
     * @param workorderId the workorder ID
     * @return optional containing the current assignment if one exists
     */
    @NonNull
    public Optional<TechnicianAssignment> getCurrentAssignment(@NonNull UUID workorderId) {
        return assignmentRepository.findByWorkorderIdAndCurrentTrue(workorderId);
    }
    
    /**
     * Get the full assignment history for a workorder, ordered newest-first.
     * 
     * @param workorderId the workorder ID
     * @return list of assignments ordered by assignedAt descending
     */
    @NonNull
    public List<TechnicianAssignment> getAssignmentHistory(@NonNull UUID workorderId) {
        return assignmentRepository.findByWorkorderIdOrderByAssignedAtDesc(workorderId);
    }
    
    /**
     * Get the previous technician ID if this workorder was reassigned.
     * 
     * @param workorderId the workorder ID
     * @return optional containing the previous technician ID if there was a reassignment
     */
    @NonNull
    public Optional<UUID> getPreviousTechnicianId(@NonNull UUID workorderId) {
        List<TechnicianAssignment> history = getAssignmentHistory(workorderId);
        if (history.size() >= 2) {
            // Second most recent assignment is the previous one
            return Optional.of(history.get(1).getTechnicianId());
        }
        return Optional.empty();
    }
    
    /**
     * Get the current workorder status.
     * 
     * @param workorderId the workorder ID
     * @return the workorder status
     * @throws NoSuchElementException if workorder not found
     */
    @NonNull
    public WorkorderStatus getWorkorderStatus(@NonNull UUID workorderId) {
        return workorderRepository.findById(workorderId)
                .map(Workorder::getStatus)
                .orElseThrow(() -> new NoSuchElementException(WORKORDER_NOT_FOUND + workorderId));
    }
    
    /**
     * Validate that assignment is allowed for the workorder's current status.
     * 
     * @param workorder the workorder
     * @throws IllegalStateException if assignment is not allowed
     */
    private void validateAssignmentAllowed(@NonNull Workorder workorder) {
        WorkorderStatus status = workorder.getStatus();
        if (status != WorkorderStatus.APPROVED 
                && status != WorkorderStatus.ASSIGNED 
                && status != WorkorderStatus.WORK_IN_PROGRESS) {
            throw new IllegalStateException(
                    "Cannot assign technician: workorder must be in APPROVED, ASSIGNED, or WORK_IN_PROGRESS status. Current status: " + status);
        }
    }
}
