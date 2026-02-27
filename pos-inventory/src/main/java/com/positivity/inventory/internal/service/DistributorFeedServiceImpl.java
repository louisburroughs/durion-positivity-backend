package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.DistributorFeedItemDto;
import com.positivity.inventory.internal.entity.DistributorFeedException;
import com.positivity.inventory.internal.entity.DistributorNormalizedInventory;
import com.positivity.inventory.internal.enums.DistributorExceptionReason;
import com.positivity.inventory.internal.repository.DistributorFeedExceptionRepository;
import com.positivity.inventory.internal.repository.DistributorNormalizedInventoryRepository;
import com.positivity.inventory.service.DistributorFeedService;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.Locale;
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
    private static final Pattern LEAD_TIME_RANGE_WITH_UNIT_PATTERN = Pattern
            .compile("^(\\d+)\\s*-\\s*(\\d+)\\s*(hour|hours|hr|hrs|day|days)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEAD_TIME_SINGLE_WITH_UNIT_PATTERN = Pattern
            .compile("^(\\d+)\\s*(hour|hours|hr|hrs|day|days)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEAD_TIME_T_PLUS_PATTERN = Pattern.compile("^T\\s*\\+\\s*(\\d+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ISO_3166_ALPHA2_PATTERN = Pattern.compile("^[A-Z]{2}$");
    private static final Pattern ISO_3166_2_PATTERN = Pattern.compile("^[A-Z]{2}-[A-Z0-9]{1,3}$");

    private final DistributorNormalizedInventoryRepository distributorNormalizedInventoryRepository;
    private final DistributorFeedExceptionRepository distributorFeedExceptionRepository;
    private final ObjectMapper objectMapper;

    public DistributorFeedServiceImpl(DistributorNormalizedInventoryRepository distributorNormalizedInventoryRepository,
            DistributorFeedExceptionRepository distributorFeedExceptionRepository,
            ObjectMapper objectMapper) {
        this.distributorNormalizedInventoryRepository = distributorNormalizedInventoryRepository;
        this.distributorFeedExceptionRepository = distributorFeedExceptionRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void processFeed(@NonNull Collection<DistributorFeedItemDto> feedItems) {
        // Issue #47: Stub normalization with exception queue for unmapped or malformed
        // records.
        log.info("Processing distributor feed with {} items", feedItems.size());

        for (DistributorFeedItemDto item : feedItems) {
            if (item.getProductId() == null) {
                queueException(item, DistributorExceptionReason.SKU_UNMAPPED);
            } else {
                LeadTimeNormalizationResult leadTime = normalizeLeadTime(item.getRawLeadTime());
                if (!leadTime.valid()) {
                    queueException(item, DistributorExceptionReason.LEAD_TIME_UNPARSABLE);
                } else {
                    String normalizedRegion = normalizeRegion(item.getRawShipFromRegion());
                    if (normalizedRegion == null) {
                        queueException(item, DistributorExceptionReason.REGION_UNMAPPED);
                    } else {
                        upsertNormalized(item, leadTime, normalizedRegion);
                    }
                }
            }
        }
    }

    private void upsertNormalized(DistributorFeedItemDto item,
            LeadTimeNormalizationResult leadTime,
            String normalizedRegion) {
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

        distributorNormalizedInventoryRepository.save(normalized);
    }

    private void queueException(DistributorFeedItemDto item,
            DistributorExceptionReason reason) {
        DistributorFeedException exception = DistributorFeedException.builder()
                .distributorId(item.getDistributorId() != null ? item.getDistributorId() : "UNKNOWN")
                .distributorSku(item.getDistributorSku())
                .reason(reason)
                .rawPayload(serializeRawPayload(item))
                .build();

        distributorFeedExceptionRepository.save(exception);
    }

    private String serializeRawPayload(DistributorFeedItemDto item) {
        try {
            return objectMapper.writeValueAsString(item);
        } catch (Exception exception) {
            log.warn("Failed to serialize distributor feed item payload; storing fallback payload", exception);
            return "DistributorFeedItemDto{distributorId=%s, distributorSku=%s, productId=%s, rawLeadTime=%s, rawShipFromRegion=%s}"
                    .formatted(item.getDistributorId(), item.getDistributorSku(), item.getProductId(),
                            item.getRawLeadTime(), item.getRawShipFromRegion());
        }
    }

    private LeadTimeNormalizationResult normalizeLeadTime(String rawLeadTime) {
        if (rawLeadTime == null || rawLeadTime.isBlank()) {
            return new LeadTimeNormalizationResult(true, null, null);
        }

        String normalized = rawLeadTime.trim();
        String normalizedUpper = normalized.toUpperCase(Locale.ROOT);

        // Qualitative values accepted by CAP-170 policy; no numeric day bounds
        // available.
        if (normalizedUpper.equals("BACKORDER")
                || normalizedUpper.equals("CALL FOR AVAILABILITY")
                || normalizedUpper.equals("CALL")
                || normalizedUpper.equals("CFA")) {
            return new LeadTimeNormalizationResult(true, null, null);
        }

        if (normalizedUpper.equals("SAME DAY") || normalizedUpper.equals("SAMEDAY")
                || normalizedUpper.equals("TODAY")) {
            return new LeadTimeNormalizationResult(true, 0, 0);
        }

        Matcher tPlusMatcher = LEAD_TIME_T_PLUS_PATTERN.matcher(normalized);
        if (tPlusMatcher.matches()) {
            int days = Integer.parseInt(tPlusMatcher.group(1));
            return new LeadTimeNormalizationResult(true, days, days);
        }

        Matcher rangeWithUnitMatcher = LEAD_TIME_RANGE_WITH_UNIT_PATTERN.matcher(normalized);
        if (rangeWithUnitMatcher.matches()) {
            int min = Integer.parseInt(rangeWithUnitMatcher.group(1));
            int max = Integer.parseInt(rangeWithUnitMatcher.group(2));
            String unit = rangeWithUnitMatcher.group(3).toLowerCase(Locale.ROOT);
            if (unit.startsWith("hour") || unit.equals("hr") || unit.equals("hrs")) {
                return new LeadTimeNormalizationResult(true, hoursToDaysCeil(min), hoursToDaysCeil(max));
            }
            return new LeadTimeNormalizationResult(true, min, max);
        }

        Matcher singleWithUnitMatcher = LEAD_TIME_SINGLE_WITH_UNIT_PATTERN.matcher(normalized);
        if (singleWithUnitMatcher.matches()) {
            int value = Integer.parseInt(singleWithUnitMatcher.group(1));
            String unit = singleWithUnitMatcher.group(2).toLowerCase(Locale.ROOT);
            if (unit.startsWith("hour") || unit.equals("hr") || unit.equals("hrs")) {
                int days = hoursToDaysCeil(value);
                return new LeadTimeNormalizationResult(true, days, days);
            }
            return new LeadTimeNormalizationResult(true, value, value);
        }

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

    private int hoursToDaysCeil(int hours) {
        return (hours + 23) / 24;
    }

    private String normalizeRegion(String rawRegion) {
        if (rawRegion == null || rawRegion.isBlank()) {
            return null;
        }

        String normalized = rawRegion.trim().toUpperCase();
        boolean iso3166Alpha2 = ISO_3166_ALPHA2_PATTERN.matcher(normalized).matches();
        boolean iso31662 = ISO_3166_2_PATTERN.matcher(normalized).matches();

        return (iso3166Alpha2 || iso31662) ? normalized : null;
    }

    private record LeadTimeNormalizationResult(boolean valid, Integer minDays, Integer maxDays) {
    }
}
