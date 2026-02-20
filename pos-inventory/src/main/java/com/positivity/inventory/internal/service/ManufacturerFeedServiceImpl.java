package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.ManufacturerFeedItemDto;
import com.positivity.inventory.internal.model.NormalizedAvailability;
import com.positivity.inventory.internal.model.UnmappedManufacturerPart;
import com.positivity.inventory.internal.model.UnmappedPartStatus;
import com.positivity.inventory.internal.repository.NormalizedAvailabilityRepository;
import com.positivity.inventory.internal.repository.UnmappedManufacturerPartRepository;
import com.positivity.inventory.service.ManufacturerFeedService;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;

/**
 * Manufacturer feed processor for normalized inventory ingestion.
 *
 * Issue: CAP-170 (#46)
 */
@Slf4j
@Service
public class ManufacturerFeedServiceImpl implements ManufacturerFeedService {

    private static final int CURRENT_SCHEMA_VERSION = 1;

    private final NormalizedAvailabilityRepository normalizedAvailabilityRepository;
    private final UnmappedManufacturerPartRepository unmappedManufacturerPartRepository;

    public ManufacturerFeedServiceImpl(NormalizedAvailabilityRepository normalizedAvailabilityRepository,
            UnmappedManufacturerPartRepository unmappedManufacturerPartRepository) {
        this.normalizedAvailabilityRepository = normalizedAvailabilityRepository;
        this.unmappedManufacturerPartRepository = unmappedManufacturerPartRepository;
    }

    @Override
    @Transactional
    public void processFeed(@NonNull Collection<ManufacturerFeedItemDto> feedItems) {
        // Issue #46: Stub ingestion path with normalization + unmapped queue routing.
        log.info("Processing manufacturer feed with {} items", feedItems.size());
        Instant receivedAt = Instant.now();

        for (ManufacturerFeedItemDto item : feedItems) {
            if (item.getProductId() == null) {
                upsertUnmapped(item, receivedAt);
                continue;
            }

            upsertNormalized(item, receivedAt);
        }
    }

    private void upsertNormalized(ManufacturerFeedItemDto item, Instant receivedAt) {
        Instant asOf = item.getAsOf() != null ? item.getAsOf() : receivedAt;
        NormalizedAvailability normalized = normalizedAvailabilityRepository
                .findByProductIdAndManufacturerIdAndAsOf(item.getProductId(), item.getManufacturerId(), asOf)
                .orElseGet(NormalizedAvailability::new);

        normalized.setProductId(item.getProductId());
        normalized.setManufacturerId(item.getManufacturerId());
        normalized.setManufacturerPartNumber(item.getManufacturerPartNumber());
        normalized.setAvailableQty(item.getAvailableQty() != null ? item.getAvailableQty() : 0);
        normalized.setUom(item.getUom() != null ? item.getUom() : "EACH");
        normalized.setUnitPriceAmount(item.getUnitPriceAmount());
        normalized.setUnitPriceCurrency(item.getUnitPriceCurrency());
        normalized.setLeadTimeDaysMin(item.getLeadTimeDaysMin());
        normalized.setLeadTimeDaysMax(item.getLeadTimeDaysMax());
        normalized.setMinOrderQty(item.getMinOrderQty());
        normalized.setPackSize(item.getPackSize() != null ? item.getPackSize() : 1);
        normalized.setSourceLocationCode(item.getSourceLocationCode());
        normalized.setAsOf(asOf);
        normalized.setReceivedAt(receivedAt);
        normalized.setSchemaVersion(CURRENT_SCHEMA_VERSION);

        normalizedAvailabilityRepository.save(normalized);
    }

    private void upsertUnmapped(ManufacturerFeedItemDto item, Instant eventTime) {
        UnmappedManufacturerPart unmapped = unmappedManufacturerPartRepository
                .findByManufacturerIdAndManufacturerPartNumber(item.getManufacturerId(),
                        item.getManufacturerPartNumber())
                .orElseGet(() -> UnmappedManufacturerPart.builder()
                        .manufacturerId(item.getManufacturerId())
                        .manufacturerPartNumber(item.getManufacturerPartNumber())
                        .firstSeen(eventTime)
                        .occurrenceCount(0)
                        .status(UnmappedPartStatus.PENDING_REVIEW)
                        .build());

        unmapped.setLastSeen(eventTime);
        unmapped.setOccurrenceCount((unmapped.getOccurrenceCount() != null ? unmapped.getOccurrenceCount() : 0) + 1);

        unmappedManufacturerPartRepository.save(unmapped);
    }
}
