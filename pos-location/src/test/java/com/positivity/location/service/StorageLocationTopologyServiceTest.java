package com.positivity.location.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.location.internal.client.LocationInventoryInquiryClient;
import com.positivity.location.internal.dto.StorageLocationTopologyResponse;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.entity.StorageLocationEntity;
import com.positivity.location.internal.enums.StorageLocationStatus;
import com.positivity.location.internal.enums.StorageLocationType;
import com.positivity.location.internal.repository.LocationRepository;
import com.positivity.location.internal.repository.StorageLocationRepository;
import com.positivity.location.internal.service.StorageLocationServiceImpl;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the unpaginated storage-location topology listing (FR-3).
 *
 * Issue: CAP-214 #655
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StorageLocationTopologyServiceTest")
class StorageLocationTopologyServiceTest {

    private static final UUID SITE_ID = UUID.nameUUIDFromBytes("site-214-655".getBytes());

    @Mock
    private StorageLocationRepository storageLocationRepository;

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private StorageLocationInventoryTransferService storageLocationInventoryTransferService;

    @Mock
    private LocationInventoryInquiryClient locationInventoryInquiryClient;

    @InjectMocks
    private StorageLocationServiceImpl storageLocationService;

    private static StorageLocationEntity entity(
            String name, StorageLocationType type, StorageLocationStatus status, StorageLocationEntity parent) {
        return StorageLocationEntity.builder()
                .id(UUID.randomUUID())
                .name(name)
                .type(type)
                .status(status)
                .site(Location.builder().id(SITE_ID).build())
                .parentStorageLocation(parent)
                .build();
    }

    @Test
    @DisplayName("#655 - topology returns every storage location regardless of status")
    void listStorageLocationTopology_mixedStatuses_returnsAllWithoutStatusFiltering() {
        StorageLocationEntity floor = entity("Floor-1", StorageLocationType.FLOOR, StorageLocationStatus.ACTIVE, null);
        StorageLocationEntity shelf =
                entity("Shelf-1", StorageLocationType.SHELF, StorageLocationStatus.INACTIVE, floor);
        StorageLocationEntity bin = entity("Bin-1", StorageLocationType.BIN, StorageLocationStatus.MAINTENANCE, shelf);
        StorageLocationEntity cage =
                entity("Cage-1", StorageLocationType.CAGE, StorageLocationStatus.QUARANTINED, floor);

        when(storageLocationRepository.findAllBySiteId(SITE_ID)).thenReturn(List.of(floor, shelf, bin, cage));

        List<StorageLocationTopologyResponse> result = storageLocationService.listStorageLocationTopology(SITE_ID);

        assertThat(result).hasSize(4);
        assertThat(result)
                .extracting(StorageLocationTopologyResponse::getStatus)
                .containsExactly("ACTIVE", "INACTIVE", "MAINTENANCE", "QUARANTINED");

        StorageLocationTopologyResponse shelfResponse = result.get(1);
        assertThat(shelfResponse.getId()).isEqualTo(shelf.getId());
        assertThat(shelfResponse.getName()).isEqualTo("Shelf-1");
        assertThat(shelfResponse.getType()).isEqualTo(StorageLocationType.SHELF);
        assertThat(shelfResponse.getParentStorageLocationId()).isEqualTo(floor.getId());

        StorageLocationTopologyResponse floorResponse = result.get(0);
        assertThat(floorResponse.getParentStorageLocationId()).isNull();

        verify(storageLocationRepository).findAllBySiteId(SITE_ID);
    }

    @Test
    @DisplayName("#655 - site without storage locations returns empty list")
    void listStorageLocationTopology_emptySite_returnsEmptyList() {
        when(storageLocationRepository.findAllBySiteId(SITE_ID)).thenReturn(List.of());

        assertThat(storageLocationService.listStorageLocationTopology(SITE_ID)).isEmpty();
    }
}
