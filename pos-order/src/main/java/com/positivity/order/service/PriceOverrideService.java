package com.positivity.order.service;

import com.positivity.order.service.model.ApplyPriceOverrideRequest;
import com.positivity.order.service.model.ApproveOverrideCommand;
import com.positivity.order.service.model.PriceOverrideDetail;
import com.positivity.order.service.model.PriceOverrideResult;
import com.positivity.order.service.model.RejectOverrideCommand;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Service for managing price override operations.
 */
public interface PriceOverrideService {

        @NonNull
        PriceOverrideResult applyPriceOverride(ApplyPriceOverrideRequest request);

        @NonNull
        PriceOverrideDetail approveOverride(UUID overrideId, ApproveOverrideCommand command);

        @NonNull
        PriceOverrideDetail rejectOverride(UUID overrideId, RejectOverrideCommand command);

        @NonNull
        PriceOverrideDetail getOverrideById(UUID overrideId);

        @NonNull
        List<PriceOverrideDetail> getOverridesByOrderId(UUID orderId);

        @NonNull
        List<PriceOverrideDetail> getPendingApprovals();

        @NonNull
        List<PriceOverrideDetail> getOverridesByDateRange(Instant startDate, Instant endDate);

        @NonNull
        List<PriceOverrideDetail> getOverridesByStatus(String status);
}
