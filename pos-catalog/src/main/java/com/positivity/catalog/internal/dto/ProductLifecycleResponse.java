package com.positivity.catalog.internal.dto;

import com.positivity.catalog.internal.entity.ProductLifecycleState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ProductLifecycleResponse {
    private UUID productId;
    private ProductLifecycleState lifecycleState;
    private Instant lifecycleStateEffectiveAt;
    private UUID lastStateChangedBy;
    private Instant lastStateChangedAt;
    private String lifecycleOverrideReason;
    private List<ReplacementOption> replacementOptions;

    @Getter
    @Builder
    public static class ReplacementOption {
        private UUID replacementId;
        private UUID replacementProductId;
        private Integer priorityOrder;
        private String notes;
        private Instant effectiveAt;
    }
}
