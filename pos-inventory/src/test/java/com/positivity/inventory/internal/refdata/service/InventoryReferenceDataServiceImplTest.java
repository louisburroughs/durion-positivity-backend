package com.positivity.inventory.internal.refdata.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.repository.LocationRefRepository;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.inventory.internal.service.LocationScopeService;
import com.positivity.security.common.LocationScopeDeniedException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

/**
 * Location scope on the reference-data placeholders (ADR-0061 §3, #1872): a named location is
 * gated; without one the empty placeholder page already satisfies any narrowing.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InventoryReferenceDataServiceImpl location scope")
class InventoryReferenceDataServiceImplTest {

    private static final UUID SITE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Mock
    private LocationRefRepository locationRefRepository;

    @Mock
    private LocationScopeService locationScopeService;

    @InjectMocks
    private InventoryReferenceDataServiceImpl service;

    @Test
    @DisplayName("listStorageLocations / listLocationZones gate a named location on inventory:location:view")
    void namedLocationIsGated() {
        service.listStorageLocations(SITE, PageRequest.of(0, 20));
        service.listLocationZones(SITE, PageRequest.of(0, 20));

        verify(locationScopeService, org.mockito.Mockito.times(2))
                .narrowTo(SITE, InventoryPermissionRegistry.LOCATION_VIEW);
    }

    @Test
    @DisplayName("a denied location propagates; nothing is served")
    void deniedLocationPropagates() {
        when(locationScopeService.narrowTo(eq(SITE), eq(InventoryPermissionRegistry.LOCATION_VIEW)))
                .thenThrow(
                        new LocationScopeDeniedException(InventoryPermissionRegistry.LOCATION_VIEW, SITE.toString()));

        assertThatThrownBy(() -> service.listStorageLocations(SITE, PageRequest.of(0, 20)))
                .isInstanceOf(LocationScopeDeniedException.class);
    }

    @Test
    @DisplayName("without a location the placeholder page is empty, whatever the caller's reach")
    void withoutLocationStaysEmpty() {
        assertThat(service.listStorageLocations(null, PageRequest.of(0, 20))).isEmpty();
        assertThat(service.listLocationZones(null, PageRequest.of(0, 20))).isEmpty();
        verify(locationScopeService, org.mockito.Mockito.times(2))
                .narrowTo(null, InventoryPermissionRegistry.LOCATION_VIEW);
    }
}
