package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.DistributorFeedItemDto;
import com.positivity.inventory.internal.model.DistributorExceptionReason;
import com.positivity.inventory.internal.model.DistributorFeedException;
import com.positivity.inventory.internal.model.DistributorNormalizedInventory;
import com.positivity.inventory.internal.repository.DistributorFeedExceptionRepository;
import com.positivity.inventory.internal.repository.DistributorNormalizedInventoryRepository;
import com.positivity.inventory.service.DistributorFeedService;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Distributor feed processor with normalization and exception queue routing.
 *
 * Issue: CAP-170 (#47)
 */
@Slf4j
@Service
public class DistributorFeedServiceImpl implements DistributorFeedService {

    private static final String POLICY_VERSION = "v1";
    private static final Pattern LEAD_TIME_RANGE_PATTERN = Pattern.compile("^(\\d+)\\s*-\\s*(\\d+)$");
    private static final Pattern LEAD_TIME_SINGLE_PATTERN = Pattern.compile("^(\\d+)$");

    private final DistributorNormalizedInventoryRepository distributorNormalizedInventoryRepository;
    private final DistributorFeedExceptionRepository distributorFeedExceptionRepository;

    public DistributorFeedServiceImpl(DistributorNormalizedInventoryRepository distributorNormalizedInventoryRepository,
            DistributorFeedExceptionRepository distributorFeedExceptionRepository) {
        this.distributorNormalizedInventoryRepository = distributorNormalizedInventoryRepository;
        this.distributorFeedExceptionRepository = distributorFeedExceptionRepository;
    }

    @Override
    @Transactional
    public void processFeed(@NonNull Collection<DistributorFeedItemDto> feedItems) {
        // Issue #47: Stub normalization with exception queue for unmapped or malformed
        // records.
        log.info("Processing distributor feed with {} items", feedItems.size());
        Instant now = Instant.now();

        for (DistributorFeedItemDto item : feedItems) {
            if (item.getProductId() == null) {
                queueException(item, DistributorExceptionReason.SKU_UNMAPPED, now);
            } else {
                LeadTimeNormalizationResult leadTime = normalizeLeadTime(item.getRawLeadTime());
                if (!leadTime.valid()) {
                    queueException(item, DistributorExceptionReason.LEAD_TIME_UNPARSABLE, now);
                } else {
                    String normalizedRegion = normalizeRegion(item.getRawShipFromRegion());
                    if (normalizedRegion == null) {
                        queueException(item, DistributorExceptionReason.REGION_UNMAPPED, now);
                    } else {
                        upsertNormalized(item, leadTime, normalizedRegion, now);
                    }
                }
            }
        }
    }

    private void upsertNormalized(DistributorFeedItemDto item,
            LeadTimeNormalizationResult leadTime,
            String normalizedRegion,
            Instant eventTime) {
        DistributorNormalizedInventory normalized = distributorNormalizedInventoryRepository
                .findByDistributorIdAndDistributorSku(item.getDistributorId(), item.getDistributorSku())
                .orElseGet(DistributorNormalizedInventory::new);

        normalized.setProductId(item.getProductId());
        normalized.setDistributorId(item.getDistributorId());
        normalized.setDistributorSku(item.getDistributorSku());
        normalized.setQuantityAvailable(item.getQuantityAvailable() != null ? item.getQuantityAvailable() : 0);
        normalized.setLeadTimeDaysMin(leadTime.minDays());
        normalized.setLeadTimeDaysMax(leadTime.maxDays());
        normalized.setShipFromRegionCode(normalizedRegion);
        normalized.setNormalizationPolicyVersion(POLICY_VERSION);
        normalized.setRawLeadTime(item.getRawLeadTime());
        normalized.setRawShipFromRegion(item.getRawShipFromRegion());
        normalized.setLastUpdatedAt(eventTime);

        distributorNormalizedInventoryRepository.save(normalized);
    }

    private void queueException(DistributorFeedItemDto item,
            DistributorExceptionReason reason,
            Instant eventTime) {
        DistributorFeedException exception = DistributorFeedException.builder()
                .distributorId(item.getDistributorId() != null ? item.getDistributorId() : "UNKNOWN")
                .distributorSku(item.getDistributorSku())
                .reason(reason)
                .rawPayload(String.valueOf(item))
                .createdAt(eventTime)
                .build();

        distributorFeedExceptionRepository.save(exception);
    }

    private LeadTimeNormalizationResult normalizeLeadTime(String rawLeadTime) {
        if (rawLeadTime == null || rawLeadTime.isBlank()) {
            return new LeadTimeNormalizationResult(true, null, null);
        }

        String normalized = rawLeadTime.trim();
        Matcher rangeMatcher = LEAD_TIME_RANGE_PATTERN.matcher(normalized);
        if (rangeMatcher.matches()) {
            return new LeadTimeNormalizationResult(true,
                    Integer.parseInt(rangeMatcher.group(1)),
                    Integer.parseInt(rangeMatcher.group(2)));
        }

        Matcher singleMatcher = LEAD_TIME_SINGLE_PATTERN.matcher(normalized);
        if (singleMatcher.matches()) {
            int value = Integer.parseInt(singleMatcher.group(1));
            return new LeadTimeNormalizationResult(true, value, value);
        }

        return new LeadTimeNormalizationResult(false, null, null);
    }

    private String normalizeRegion(String rawRegion) {
        if (rawRegion == null || rawRegion.isBlank()) {
            return null;
        }

        String normalized = rawRegion.trim().toUpperCase();
        if (normalized.length() < 2 || normalized.length() > 12) {
            return null;
        }
        return normalized;
    }

    private record LeadTimeNormalizationResult(boolean valid, Integer minDays, Integer maxDays) {
    }
}
