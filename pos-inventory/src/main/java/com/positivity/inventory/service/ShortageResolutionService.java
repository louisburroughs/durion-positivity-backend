package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.ShortageOptionDto;
import com.positivity.inventory.internal.dto.ShortageResolutionResultDto;
import com.positivity.inventory.internal.dto.ShortageResolveRequest;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Computed shortage options and atomic resolution execution (odoo-parity G2, issue #1049).
 *
 * <p>{@link #computeShortageOptions} returns live options built from the G1 backorder path, the X1
 * substitution replica, cross-site C1 transfer surplus, and the F4 purchase path — each with an
 * expected-resolution date and a cost delta where computable. {@link #resolveShortage} executes a
 * chosen option atomically and idempotently.
 */
public interface ShortageResolutionService {

    /**
     * Computes the resolution options available for a shortage.
     *
     * @param allocationId the allocation experiencing the shortage
     * @param workorderLineId the workorder line whose demand is short (optional)
     * @param sku the SKU / stock-item identifier that is short
     * @param shortQuantity the quantity that is short (positive)
     * @param locationId the site the demand is short at (optional)
     * @return the computed options
     */
    @NonNull
    List<ShortageOptionDto> computeShortageOptions(
            @NonNull UUID allocationId,
            @Nullable UUID workorderLineId,
            @NonNull String sku,
            int shortQuantity,
            @Nullable UUID locationId);

    /**
     * Executes the chosen resolution option atomically and idempotently. A replayed request carrying
     * the same {@code idempotencyKey} returns the original result without re-executing the option.
     *
     * @param request the resolve request
     * @return the resolution result, referencing any created artifact
     */
    @NonNull
    ShortageResolutionResultDto resolveShortage(@NonNull ShortageResolveRequest request);
}
