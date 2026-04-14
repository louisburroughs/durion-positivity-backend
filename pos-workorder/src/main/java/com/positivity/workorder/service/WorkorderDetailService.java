package com.positivity.workorder.service;

import com.positivity.workorder.internal.dto.WorkorderDetailResponse;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Service for retrieving workorder detail with role-based visibility.
 *
 * <p>
 * Implements CAP-005 Story #155 - Role-Based Visibility
 */
public interface WorkorderDetailService {

    /**
     * Get comprehensive workorder detail with role-based field visibility.
     *
     * @param workorderId     the workorder ID
     * @param userAuthorities the set of user authorities for capability
     *                        determination
     * @return comprehensive workorder detail response
     * @throws IllegalArgumentException if workorder not found
     */
    @NonNull
    WorkorderDetailResponse getWorkorderDetail(@NonNull UUID workorderId, @NonNull Set<String> userAuthorities);
}
