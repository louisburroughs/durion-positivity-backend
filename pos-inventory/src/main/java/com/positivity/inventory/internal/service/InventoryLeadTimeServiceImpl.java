package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.LeadTimeView;
import com.positivity.inventory.internal.exception.InvalidInventoryAvailabilityRequestException;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.DistributorNormalizedInventoryRepository;
import com.positivity.inventory.internal.repository.NormalizedAvailabilityRepository;
import com.positivity.inventory.service.InventoryLeadTimeService;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves dynamic lead-time estimates from normalized inventory/supply
 * sources.
 */
@Service
public class InventoryLeadTimeServiceImpl implements InventoryLeadTimeService {
    private final Clock clock;

    private final DistributorNormalizedInventoryRepository distributorNormalizedInventoryRepository;
    private final NormalizedAvailabilityRepository normalizedAvailabilityRepository;

    public InventoryLeadTimeServiceImpl(
            DistributorNormalizedInventoryRepository distributorNormalizedInventoryRepository,
            NormalizedAvailabilityRepository normalizedAvailabilityRepository,
            Clock clock) {
        this.clock = clock;
        this.distributorNormalizedInventoryRepository = distributorNormalizedInventoryRepository;
        this.normalizedAvailabilityRepository = normalizedAvailabilityRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull LeadTimeView queryLeadTime(
            @NonNull UUID productId,
            @Nullable UUID locationId,
            @Nullable UUID storageLocationId) {
        if (productId == null) {
            throw new InvalidInventoryAvailabilityRequestException("productId is required");
        }

        UUID resolvedLocationId = storageLocationId != null ? storageLocationId : locationId;

        LeadTimeCandidate distributorCandidate = toCandidate(
                distributorNormalizedInventoryRepository.findLeadTimeAggregateByProductId(productId),
                "INVENTORY",
                "HIGH");
        LeadTimeCandidate manufacturerCandidate = toCandidate(
                normalizedAvailabilityRepository.findLeadTimeAggregateByProductId(productId), "SUPPLY_CHAIN", "MEDIUM");

        LeadTimeCandidate selected = distributorCandidate != null ? distributorCandidate : manufacturerCandidate;
        if (selected == null) {
            throw new ResourceNotFoundException("LeadTime", productId.toString());
        }

        return LeadTimeView.builder()
                .productId(productId)
                .locationId(resolvedLocationId)
                .source(selected.source())
                .minDays(selected.minDays())
                .maxDays(selected.maxDays())
                .displayText(formatDisplayText(selected.minDays(), selected.maxDays()))
                .confidence(selected.confidence())
                .asOf(selected.asOf())
                .build();
    }

    private LeadTimeCandidate toCandidate(
            DistributorNormalizedInventoryRepository.LeadTimeAggregate aggregate, String source, String confidence) {
        if (aggregate == null) {
            return null;
        }
        return toCandidate(aggregate.getMinDays(), aggregate.getMaxDays(), aggregate.getAsOf(), source, confidence);
    }

    private LeadTimeCandidate toCandidate(
            NormalizedAvailabilityRepository.LeadTimeAggregate aggregate, String source, String confidence) {
        if (aggregate == null) {
            return null;
        }
        return toCandidate(aggregate.getMinDays(), aggregate.getMaxDays(), aggregate.getAsOf(), source, confidence);
    }

    private LeadTimeCandidate toCandidate(
            Integer minDays, Integer maxDays, Instant asOf, String source, String confidence) {
        if (minDays == null && maxDays == null) {
            return null;
        }

        int resolvedMin = minDays != null ? minDays : maxDays;
        int resolvedMax = maxDays != null ? maxDays : minDays;
        if (resolvedMin > resolvedMax) {
            int tmp = resolvedMin;
            resolvedMin = resolvedMax;
            resolvedMax = tmp;
        }

        return new LeadTimeCandidate(
                resolvedMin, resolvedMax, asOf != null ? asOf : Instant.now(clock), source, confidence);
    }

    private String formatDisplayText(Integer minDays, Integer maxDays) {
        if (minDays == null && maxDays == null) {
            return null;
        }
        if (minDays != null && maxDays != null) {
            if (minDays.equals(maxDays)) {
                return minDays + " business days";
            }
            return minDays + "-" + maxDays + " business days";
        }
        Integer value = minDays != null ? minDays : maxDays;
        return value + " business days";
    }

    private record LeadTimeCandidate(
            Integer minDays, Integer maxDays, Instant asOf, String source, String confidence) {
    }
}
