package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.dto.WorkorderPartUsageEventResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface WorkorderPartUsageService {

    /**
     * @param uomCode the unit {@code quantity} is expressed in; {@code null} means the product's
     *     base unit, matching pre-#1415 behavior exactly (ADR-0055 stage 3)
     */
    @NonNull
    WorkorderPartUsageEventResponse issuePartQuantity(
            @NonNull UUID workorderId,
            @NonNull UUID partLineId,
            @NonNull BigDecimal quantity,
            @Nullable String uomCode,
            @Nullable String idempotencyKey);

    /**
     * @param uomCode the unit {@code quantity} is expressed in; {@code null} means the product's
     *     base unit, matching pre-#1415 behavior exactly (ADR-0055 stage 3)
     */
    @NonNull
    WorkorderPartUsageEventResponse consumePartQuantity(
            @NonNull UUID workorderId,
            @NonNull UUID partLineId,
            @NonNull BigDecimal quantity,
            @Nullable String uomCode,
            @Nullable String idempotencyKey);

    /**
     * @param uomCode the unit {@code quantity} is expressed in; {@code null} means the product's
     *     base unit, matching pre-#1415 behavior exactly (ADR-0055 stage 3)
     */
    @NonNull
    WorkorderPartUsageEventResponse returnPartQuantity(
            @NonNull UUID workorderId,
            @NonNull UUID partLineId,
            @NonNull BigDecimal quantity,
            @Nullable String uomCode,
            @Nullable String idempotencyKey);

    @NonNull
    List<WorkorderPartUsageEventResponse> getUsageHistory(@NonNull UUID workorderId, @NonNull UUID partLineId);

    @NonNull
    List<WorkorderPartUsageEventResponse> getAllUsageHistory(@NonNull UUID workorderId);
}
