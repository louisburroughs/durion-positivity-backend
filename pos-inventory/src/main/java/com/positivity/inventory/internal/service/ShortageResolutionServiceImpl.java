package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.client.ExternalAvailabilityClient;
import com.positivity.inventory.internal.client.ProductSubstituteClient;
import com.positivity.inventory.internal.dto.ShortageOptionDto;
import com.positivity.inventory.internal.dto.ShortageResolutionResultDto;
import com.positivity.inventory.internal.dto.ShortageResolveRequest;
import com.positivity.inventory.service.ShortageResolutionService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ShortageResolutionServiceImpl implements ShortageResolutionService {

    @SuppressWarnings("unused")
    private final ProductSubstituteClient productSubstituteClient;

    @SuppressWarnings("unused")
    private final ExternalAvailabilityClient externalAvailabilityClient;

    @SuppressWarnings("unused")
    private final Executor shortageResolutionExecutor;

    private final Clock clock;

    public ShortageResolutionServiceImpl(
            @NonNull ProductSubstituteClient productSubstituteClient,
            @NonNull ExternalAvailabilityClient externalAvailabilityClient,
            @Qualifier("shortageResolutionExecutor") @NonNull Executor shortageResolutionExecutor,
            @NonNull Clock clock) {
        this.productSubstituteClient = productSubstituteClient;
        this.externalAvailabilityClient = externalAvailabilityClient;
        this.shortageResolutionExecutor = shortageResolutionExecutor;
        this.clock = clock;
    }

    @Override
    public @NonNull List<ShortageOptionDto> listShortageOptions(@NonNull UUID allocationId) {
        return List.of(
                ShortageOptionDto.builder()
                        .allocationId(allocationId)
                        .resolution("BACKORDER")
                        .description("Place on backorder")
                        .estimatedDays(7)
                        .build(),
                ShortageOptionDto.builder()
                        .allocationId(allocationId)
                        .resolution("CANCEL")
                        .description("Cancel the allocation")
                        .estimatedDays(0)
                        .build());
    }

    @Override
    public @NonNull ShortageResolutionResultDto resolveShortage(@NonNull ShortageResolveRequest request) {
        return ShortageResolutionResultDto.builder()
                .allocationId(request.getAllocationId())
                .resolution(request.getResolution())
                .resolvedAt(Instant.now(clock))
                .status("RESOLVED")
                .build();
    }
}
