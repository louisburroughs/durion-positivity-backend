package com.positivity.location.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.positivity.location.internal.dto.LocationRef;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.repository.LocationRepository;
import com.positivity.location.internal.service.LocationRosterServiceImpl;

/**
 * Unit tests for LocationRosterService — reconciliation roster for sync
 * consumers.
 *
 * <p>
 * Business rules verified:
 * <ul>
 * <li>Roster returns paginated location refs with all fields mapped</li>
 * <li>Status filter narrows results to matching locations only</li>
 * <li>sinceUpdatedAt filter returns only recently-updated locations</li>
 * <li>Empty result set returns empty page (no errors)</li>
 * <li>toLocationRef maps id, name, code, status, hrLocationId, updatedAt
 * correctly</li>
 * </ul>
 *
 * Issue: CAP-214 #40
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LocationRosterServiceTest")
class LocationRosterServiceTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    private LocationRepository locationRepository;

    @InjectMocks
    private LocationRosterServiceImpl locationRosterService;

    // -------------------------------------------------------------------------
    // getRoster — no filter
    // -------------------------------------------------------------------------

    /**
     * No filter: repository returns all locations and the service pages them as
     * LocationRefs.
     */
    @Test
    @DisplayName("#40 - getRoster with no filter returns all locations paged")
    void getRoster_noFilter_returnsAllLocationsPage() {
        Pageable pageable = PageRequest.of(0, 10);
        Location loc1 = buildLocation(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Shop A", "SHOP-A",
                "ACTIVE");
        Location loc2 = buildLocation(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Shop B", "SHOP-B",
                "INACTIVE");
        Page<Location> locationPage = new PageImpl<>(List.of(loc1, loc2), pageable, 2);

        when(locationRepository.findAll(any(Pageable.class))).thenReturn(locationPage);

        Page<LocationRef> result = locationRosterService.getRoster(null, null, pageable);

        assertThat(result).isNotNull();
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).hasSize(2);
    }

    // -------------------------------------------------------------------------
    // getRoster — status filter
    // -------------------------------------------------------------------------

    /**
     * Status=ACTIVE filter: only active locations are returned.
     */
    @Test
    @DisplayName("#40 - getRoster with status=ACTIVE returns only active locations")
    void getRoster_filterByStatus_returnsFiltered() {
        Pageable pageable = PageRequest.of(0, 10);
        Location active = buildLocation(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Active Shop",
                "ACT-001", "ACTIVE");
        Page<Location> activePage = new PageImpl<>(List.of(active), pageable, 1);

        when(locationRepository.findByStatus(eq("ACTIVE"), any(Pageable.class))).thenReturn(activePage);

        Page<LocationRef> result = locationRosterService.getRoster("ACTIVE", null, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getStatus()).isEqualTo("ACTIVE");
    }

    // -------------------------------------------------------------------------
    // getRoster — sinceUpdatedAt filter
    // -------------------------------------------------------------------------

    /**
     * sinceUpdatedAt filter: repository is queried with the timestamp, only
     * locations updated after it are returned.
     */
    @Test
    @DisplayName("#40 - getRoster with sinceUpdatedAt returns recently-updated locations")
    void getRoster_filterBySinceUpdatedAt_returnsRecent() {
        Pageable pageable = PageRequest.of(0, 10);
        Instant since = Instant.parse("2026-01-01T00:00:00Z");
        Location recent = buildLocation(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Recent Shop",
                "REC-001", "ACTIVE");
        Page<Location> recentPage = new PageImpl<>(List.of(recent), pageable, 1);

        when(locationRepository.findByUpdatedAtAfter(eq(since), any(Pageable.class))).thenReturn(recentPage);

        Page<LocationRef> result = locationRosterService.getRoster(null, since, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getName()).isEqualTo("Recent Shop");
    }

    // -------------------------------------------------------------------------
    // getRoster — empty result
    // -------------------------------------------------------------------------

    /**
     * No matching locations: service returns an empty page without error.
     */
    @Test
    @DisplayName("#40 - getRoster with no matching locations returns empty page")
    void getRoster_emptyResult_returnsEmptyPage() {
        Pageable pageable = PageRequest.of(0, 10);
        when(locationRepository.findAll(any(Pageable.class))).thenReturn(Page.empty(pageable));

        Page<LocationRef> result = locationRosterService.getRoster(null, null, pageable);

        assertThat(result).isNotNull();
        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getContent()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // toLocationRef — field mapping
    // -------------------------------------------------------------------------

    /**
     * toLocationRef maps all fields from Location entity to LocationRef correctly,
     * including nullable hrLocationId and updatedAt.
     */
    @Test
    @DisplayName("#40 - toLocationRef maps id, name, code, status, hrLocationId, updatedAt")
    void toLocationRef_mapsAllFields() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Instant updatedAt = Instant.parse("2026-02-15T10:00:00Z");
        Location location = Location.builder()
                .id(id)
                .name("Field Mapping Shop")
                .code("FM-001")
                .status("ACTIVE")
                .hrLocationId("HR-XYZ-001")
                .updatedAt(updatedAt)
                .build();

        LocationRef ref = locationRosterService.toLocationRef(location);

        assertThat(ref.getId()).isEqualTo(id);
        assertThat(ref.getName()).isEqualTo("Field Mapping Shop");
        assertThat(ref.getCode()).isEqualTo("FM-001");
        assertThat(ref.getStatus()).isEqualTo("ACTIVE");
        assertThat(ref.getHrLocationId()).isEqualTo("HR-XYZ-001");
        assertThat(ref.getUpdatedAt()).isEqualTo(updatedAt);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Location buildLocation(UUID id, String name, String code, String status) {
        return Location.builder()
                .id(id)
                .name(name)
                .code(code)
                .status(status)
                .build();
    }
}
