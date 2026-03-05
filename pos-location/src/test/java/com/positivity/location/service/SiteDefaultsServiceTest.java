package com.positivity.location.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.positivity.location.internal.dto.SiteDefaultsRequest;
import com.positivity.location.internal.dto.SiteDefaultsResponse;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.entity.StorageLocationEntity;
import com.positivity.location.internal.enums.StorageLocationType;
import com.positivity.location.internal.repository.LocationRepository;
import com.positivity.location.internal.repository.StorageLocationRepository;
import com.positivity.location.internal.service.SiteDefaultsServiceImpl;

/**
 * Unit tests for SiteDefaultsService — configure and retrieve default staging
 * and quarantine storage locations for a Site.
 *
 * <p>
 * Business rules verified:
 * <ul>
 * <li>Staging and quarantine IDs must differ (DEFAULT_LOCATION_ROLE_CONFLICT →
 * 400)</li>
 * <li>Site must exist (404 if not)</li>
 * <li>Both storage locations must belong to the specified site (422 if
 * not)</li>
 * <li>GET returns nulls if no defaults configured yet</li>
 * </ul>
 *
 * Issue: CAP-214 #38
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SiteDefaultsServiceTest")
class SiteDefaultsServiceTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private StorageLocationRepository storageLocationRepository;

    @InjectMocks
    private SiteDefaultsServiceImpl siteDefaultsService;

    // -------------------------------------------------------------------------
    // configureDefaults
    // -------------------------------------------------------------------------

    /**
     * Happy path: staging != quarantine, both belong to the same site.
     * Expects a populated SiteDefaultsResponse.
     */
    @Test
    @DisplayName("#38 - valid configure returns SiteDefaultsResponse")
    void configureDefaults_success_returnsResponse() {
        UUID siteId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID stagingId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID quarantineId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        when(locationRepository.findById(siteId)).thenReturn(Optional.of(site(siteId)));
        when(storageLocationRepository.findByIdAndSiteId(stagingId, siteId))
                .thenReturn(Optional.of(storageLocation(stagingId, siteId)));
        when(storageLocationRepository.findByIdAndSiteId(quarantineId, siteId))
                .thenReturn(Optional.of(storageLocation(quarantineId, siteId)));

        SiteDefaultsRequest request = new SiteDefaultsRequest(stagingId, quarantineId);
        SiteDefaultsResponse response = siteDefaultsService.configureDefaults(siteId, request);

        assertThat(response).isNotNull();
        assertThat(response.getDefaultStagingLocationId()).isEqualTo(stagingId);
        assertThat(response.getDefaultQuarantineLocationId()).isEqualTo(quarantineId);
        assertThat(response.getSiteId()).isEqualTo(siteId);
    }

    /**
     * Staging and quarantine IDs are the same — must throw 400 with
     * DEFAULT_LOCATION_ROLE_CONFLICT.
     */
    @Test
    @DisplayName("#38 - same staging and quarantine ID throws 400 DEFAULT_LOCATION_ROLE_CONFLICT")
    void configureDefaults_sameLocation_throwsConflict() {
        UUID siteId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID sharedId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        when(locationRepository.findById(siteId)).thenReturn(Optional.of(site(siteId)));

        SiteDefaultsRequest request = new SiteDefaultsRequest(sharedId, sharedId);

        assertThatThrownBy(() -> siteDefaultsService.configureDefaults(siteId, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(rse.getReason()).contains("DEFAULT_LOCATION_ROLE_CONFLICT");
                });
    }

    /**
     * Site does not exist — must throw 404 Not Found.
     */
    @Test
    @DisplayName("#38 - site not found throws 404")
    void configureDefaults_siteNotFound_throwsNotFound() {
        UUID siteId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID stagingId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID quarantineId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        when(locationRepository.findById(siteId)).thenReturn(Optional.empty());

        SiteDefaultsRequest request = new SiteDefaultsRequest(stagingId, quarantineId);

        assertThatThrownBy(() -> siteDefaultsService.configureDefaults(siteId, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    /**
     * Staging storage location does not belong to the specified site — must throw
     * 422 Unprocessable Entity.
     */
    @Test
    @DisplayName("#38 - staging location not belonging to site throws 422")
    void configureDefaults_stagingLocationNotBelongsToSite_throwsValidationError() {
        UUID siteId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID stagingId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID quarantineId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        when(locationRepository.findById(siteId)).thenReturn(Optional.of(site(siteId)));
        when(storageLocationRepository.findByIdAndSiteId(stagingId, siteId))
                .thenReturn(Optional.empty());
        when(storageLocationRepository.findByIdAndSiteId(quarantineId, siteId))
                .thenReturn(Optional.of(storageLocation(quarantineId, siteId)));

        SiteDefaultsRequest request = new SiteDefaultsRequest(stagingId, quarantineId);

        assertThatThrownBy(() -> siteDefaultsService.configureDefaults(siteId, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                });
    }

    /**
     * Quarantine storage location does not belong to the specified site — must
     * throw 422 Unprocessable Entity.
     */
    @Test
    @DisplayName("#38 - quarantine location not belonging to site throws 422")
    void configureDefaults_quarantineLocationNotBelongsToSite_throwsValidationError() {
        UUID siteId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID stagingId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID quarantineId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        when(locationRepository.findById(siteId)).thenReturn(Optional.of(site(siteId)));
        when(storageLocationRepository.findByIdAndSiteId(stagingId, siteId))
                .thenReturn(Optional.of(storageLocation(stagingId, siteId)));
        when(storageLocationRepository.findByIdAndSiteId(quarantineId, siteId))
                .thenReturn(Optional.empty());

        SiteDefaultsRequest request = new SiteDefaultsRequest(stagingId, quarantineId);

        assertThatThrownBy(() -> siteDefaultsService.configureDefaults(siteId, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                });
    }

    // -------------------------------------------------------------------------
    // getDefaults
    // -------------------------------------------------------------------------

    /**
     * Happy path: site has defaults configured — returns populated response.
     */
    @Test
    @DisplayName("#38 - getDefaults with existing defaults returns populated response")
    void getDefaults_success_returnsResponse() {
        UUID siteId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        Location location = site(siteId);
        location.setDefaultStagingLocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        location.setDefaultQuarantineLocationId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        when(locationRepository.findById(siteId)).thenReturn(Optional.of(location));

        SiteDefaultsResponse response = siteDefaultsService.getDefaults(siteId);

        assertThat(response).isNotNull();
        assertThat(response.getSiteId()).isEqualTo(siteId);
    }

    /**
     * Site does not exist for getDefaults — must throw 404 Not Found.
     */
    @Test
    @DisplayName("#38 - getDefaults site not found throws 404")
    void getDefaults_siteNotFound_throwsNotFound() {
        UUID siteId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        when(locationRepository.findById(siteId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> siteDefaultsService.getDefaults(siteId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    /**
     * Site exists but no defaults have been configured yet — returns response with
     * null staging and quarantine IDs.
     */
    @Test
    @DisplayName("#38 - getDefaults with no defaults configured returns response with nulls")
    void getDefaults_noDefaultsConfigured_returnsEmptyDefaults() {
        UUID siteId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        when(locationRepository.findById(siteId)).thenReturn(Optional.of(site(siteId)));

        SiteDefaultsResponse response = siteDefaultsService.getDefaults(siteId);

        assertThat(response).isNotNull();
        assertThat(response.getSiteId()).isEqualTo(siteId);
        assertThat(response.getDefaultStagingLocationId()).isNull();
        assertThat(response.getDefaultQuarantineLocationId()).isNull();
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private StorageLocationEntity storageLocation(UUID id, UUID siteId) {
        return StorageLocationEntity.builder()
                .id(id)
                .name("Location-" + id)
                .type(StorageLocationType.BIN)
                .siteId(siteId)
                .build();
    }

    private Location site(UUID siteId) {
        return Location.builder()
                .id(siteId)
                .name("Site-" + siteId)
                .active(true)
                .build();
    }
}
