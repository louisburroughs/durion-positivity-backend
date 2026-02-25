package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.PutawayExecutionRequest;
import com.positivity.inventory.internal.dto.putaway.PutawayExecutionResponse;
import org.jspecify.annotations.NonNull;

public interface PutawayExecuteService {
    /**
     * Executes a put-away move atomically:
     * - Validates source on-hand, destination compatibility and capacity
     * - Decrements on-hand at source location
     * - Increments on-hand at destination location
     * - Creates immutable InventoryLedgerEntry with type PUTAWAY
     * - Marks PutawayTask as COMPLETED
     * - Emits audit entry
     *
     * @param taskId  the PutawayTask to execute
     * @param request the execution request with scan data
     * @return PutawayExecutionResponse with result details
     * @throws com.positivity.inventory.internal.exception.LocationNotValidForSkuException   if
     *                                                                                       destination
     *                                                                                       invalid
     * @throws com.positivity.inventory.internal.exception.LocationAtCapacityException       if
     *                                                                                       destination
     *                                                                                       full
     * @throws com.positivity.inventory.internal.exception.NoOnHandAtSourceLocationException if
     *                                                                                       source
     *                                                                                       empty
     */
    @NonNull
    PutawayExecutionResponse executePutaway(@NonNull String taskId, @NonNull PutawayExecutionRequest request);
}
