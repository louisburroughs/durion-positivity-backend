package com.positivity.inventory.service;

import java.util.List;
import java.util.UUID;

import com.positivity.inventory.dto.cyclecount.CountResponse;
import com.positivity.inventory.dto.cyclecount.SubmitCountRequest;
import com.positivity.inventory.dto.cyclecount.SubmitRecountRequest;
import com.positivity.inventory.entity.CountEntry;
import com.positivity.inventory.entity.CycleCountTask;

/**
 * Service for cycle count operations.
 * 
 * <p>Implements requirements from issue #27:
 * <ul>
 *   <li>Submit counts and calculate variance</li>
 *   <li>Support recounts with limit enforcement</li>
 *   <li>Permission-based recount triggering</li>
 *   <li>Investigation workflow for exceeded limits</li>
 * </ul>
 */
public interface CycleCountService {
    
    /**
     * Submit a count for a cycle count task.
     * Creates the first CountEntry for the task.
     *
     * @param request the count submission request
     * @return the count response with variance details
     */
    CountResponse submitCount(SubmitCountRequest request);
    
    /**
     * Submit a recount for a cycle count task.
     * Creates a new CountEntry linked to the previous count.
     * Enforces recount limits and permission checks.
     *
     * @param request the recount submission request
     * @return the recount response with variance details
     */
    CountResponse submitRecount(SubmitRecountRequest request);
    
    /**
     * Get a cycle count task by ID.
     *
     * @param taskId the task ID
     * @return the task entity
     */
    CycleCountTask getTask(UUID taskId);
    
    /**
     * Get all count entries for a task, ordered by sequence.
     *
     * @param taskId the task ID
     * @return list of count entries
     */
    List<CountEntry> getCountHistory(UUID taskId);
    
    /**
     * Get tasks assigned to an auditor.
     *
     * @param auditorId the auditor ID
     * @return list of assigned tasks
     */
    List<CycleCountTask> getTasksByAuditor(String auditorId);
}
