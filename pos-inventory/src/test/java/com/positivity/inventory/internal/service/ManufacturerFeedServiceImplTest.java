package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.ManufacturerFeedItemDto;
import com.positivity.inventory.internal.model.NormalizedAvailability;
import com.positivity.inventory.internal.model.UnmappedManufacturerPart;
import com.positivity.inventory.internal.model.UnmappedPartStatus;
import com.positivity.inventory.internal.repository.NormalizedAvailabilityRepository;
import com.positivity.inventory.internal.repository.UnmappedManufacturerPartRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class ManufacturerFeedServiceImplTest {

    @Mock
    private NormalizedAvailabilityRepository normalizedAvailabilityRepository;

    @Mock
    private UnmappedManufacturerPartRepository unmappedManufacturerPartRepository;

    @Captor
    private ArgumentCaptor<NormalizedAvailability> normalizedCaptor;

    @Captor
    private ArgumentCaptor<UnmappedManufacturerPart> unmappedCaptor;

    private ManufacturerFeedServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ManufacturerFeedServiceImpl(normalizedAvailabilityRepository, unmappedManufacturerPartRepository);
    }

    @Test
    void processFeed_savesNormalizedWithDefaultsWhenProductMapped() {
        ManufacturerFeedItemDto item = baseItemBuilder()
                .uom(null)
                .packSize(null)
                .availableQty(null)
                .asOf(null)
                .build();

        when(normalizedAvailabilityRepository.findByProductIdAndManufacturerIdAndAsOf(
                eq(item.getProductId()),
                eq(item.getManufacturerId()),
                any(Instant.class))).thenReturn(Optional.empty());

        service.processFeed(List.of(item));

        verify(normalizedAvailabilityRepository).save(normalizedCaptor.capture());
        verify(unmappedManufacturerPartRepository, never()).save(org.mockito.ArgumentMatchers.any());

        NormalizedAvailability saved = normalizedCaptor.getValue();
        assertThat(saved.getProductId()).isEqualTo(item.getProductId());
        assertThat(saved.getManufacturerId()).isEqualTo(item.getManufacturerId());
        assertThat(saved.getManufacturerPartNumber()).isEqualTo(item.getManufacturerPartNumber());
        assertThat(saved.getAvailableQty()).isEqualTo(0);
        assertThat(saved.getUom()).isEqualTo("EACH");
        assertThat(saved.getPackSize()).isEqualTo(1);
        assertThat(saved.getSchemaVersion()).isEqualTo(1);
        assertThat(saved.getAsOf()).isNotNull();
        assertThat(saved.getReceivedAt()).isNotNull();
    }

    @Test
    void processFeed_updatesExistingNormalizedWhenRecordExists() {
        Instant asOf = Instant.parse("2026-02-20T00:00:00Z");
        ManufacturerFeedItemDto item = baseItemBuilder()
                .asOf(asOf)
                .availableQty(25)
                .uom("BOX")
                .packSize(4)
                .build();

        NormalizedAvailability existing = new NormalizedAvailability();
        existing.setProductId(item.getProductId());
        existing.setManufacturerId(item.getManufacturerId());
        existing.setAsOf(asOf);

        when(normalizedAvailabilityRepository.findByProductIdAndManufacturerIdAndAsOf(
                item.getProductId(), item.getManufacturerId(), asOf)).thenReturn(Optional.of(existing));

        service.processFeed(List.of(item));

        verify(normalizedAvailabilityRepository).save(normalizedCaptor.capture());
        NormalizedAvailability saved = normalizedCaptor.getValue();
        assertThat(saved).isSameAs(existing);
        assertThat(saved.getAsOf()).isEqualTo(asOf);
        assertThat(saved.getAvailableQty()).isEqualTo(25);
        assertThat(saved.getUom()).isEqualTo("BOX");
        assertThat(saved.getPackSize()).isEqualTo(4);
    }

    @Test
    void processFeed_routesUnmappedToQueueWhenProductIdMissing() {
        ManufacturerFeedItemDto item = baseItemBuilder()
                .productId(null)
                .build();

        when(unmappedManufacturerPartRepository.findByManufacturerIdAndManufacturerPartNumber(
                item.getManufacturerId(), item.getManufacturerPartNumber())).thenReturn(Optional.empty());

        service.processFeed(List.of(item));

        verify(unmappedManufacturerPartRepository).save(unmappedCaptor.capture());
        verify(normalizedAvailabilityRepository, never()).save(org.mockito.ArgumentMatchers.any());

        UnmappedManufacturerPart saved = unmappedCaptor.getValue();
        assertThat(saved.getManufacturerId()).isEqualTo(item.getManufacturerId());
        assertThat(saved.getManufacturerPartNumber()).isEqualTo(item.getManufacturerPartNumber());
        assertThat(saved.getStatus()).isEqualTo(UnmappedPartStatus.PENDING_REVIEW);
        assertThat(saved.getOccurrenceCount()).isEqualTo(1);
        assertThat(saved.getFirstSeen()).isNotNull();
        assertThat(saved.getLastSeen()).isNotNull();
    }

    @Test
    void processFeed_incrementsOccurrenceForExistingUnmappedPart() {
        ManufacturerFeedItemDto item = baseItemBuilder()
                .productId(null)
                .build();

        Instant firstSeen = Instant.parse("2026-01-01T00:00:00Z");
        UnmappedManufacturerPart existing = UnmappedManufacturerPart.builder()
                .manufacturerId(item.getManufacturerId())
                .manufacturerPartNumber(item.getManufacturerPartNumber())
                .firstSeen(firstSeen)
                .lastSeen(firstSeen)
                .occurrenceCount(2)
                .status(UnmappedPartStatus.PENDING_REVIEW)
                .build();

        when(unmappedManufacturerPartRepository.findByManufacturerIdAndManufacturerPartNumber(
                item.getManufacturerId(), item.getManufacturerPartNumber())).thenReturn(Optional.of(existing));

        service.processFeed(List.of(item));

        verify(unmappedManufacturerPartRepository).save(unmappedCaptor.capture());
        UnmappedManufacturerPart saved = unmappedCaptor.getValue();
        assertThat(saved).isSameAs(existing);
        assertThat(saved.getFirstSeen()).isEqualTo(firstSeen);
        assertThat(saved.getOccurrenceCount()).isEqualTo(3);
        assertThat(saved.getLastSeen()).isNotNull();
        assertThat(saved.getLastSeen()).isAfterOrEqualTo(firstSeen);
    }

    private ManufacturerFeedItemDto.ManufacturerFeedItemDtoBuilder baseItemBuilder() {
        return ManufacturerFeedItemDto.builder()
                .productId(UUID.randomUUID())
                .manufacturerId("MFG-1")
                .manufacturerPartNumber("PART-1")
                .availableQty(10)
                .uom("EACH")
                .packSize(1);
    }
}
