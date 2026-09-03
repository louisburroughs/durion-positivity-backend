package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.positivity.shopmanager.internal.entity.Shop;
import com.positivity.shopmanager.internal.repository.ExtBayReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtMobileUnitReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtPersonReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtVehicleReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtWorkorderReplicaRepository;
import com.positivity.shopmanager.internal.repository.MechanicRepository;
import com.positivity.shopmanager.internal.repository.ShopRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The default {@code date} is the location's local today, not the server's (#1658 AC2).
 *
 * <p>This needs a frozen clock, so it is a plain unit test rather than part of the
 * persistence-backed suite: the whole point is the hour at which the calendar day rolls over, and
 * a test that reads the real clock either asserts nothing or fails once a night.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ShopDashboardServiceImpl — date resolution")
class ShopDashboardDateResolutionTest {

    /** 03:30 UTC on 4 September — still 3 September in New York, already 4 September in Paris. */
    private static final Instant NOW = Instant.parse("2026-09-04T03:30:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private ShopRepository shopRepository;

    @Mock
    private ExtBayReplicaRepository extBayReplicaRepository;

    @Mock
    private ExtMobileUnitReplicaRepository extMobileUnitReplicaRepository;

    @Mock
    private ExtWorkorderReplicaRepository extWorkorderReplicaRepository;

    @Mock
    private ExtVehicleReplicaRepository extVehicleReplicaRepository;

    @Mock
    private ExtPersonReplicaRepository extPersonReplicaRepository;

    @Mock
    private MechanicRepository mechanicRepository;

    private ShopDashboardServiceImpl service;
    private final UUID locationId = UUID.fromString("018e1c9f-6b5a-7890-abcd-1234567890ab");

    @BeforeEach
    void setUp() {
        service = new ShopDashboardServiceImpl(
                clock,
                shopRepository,
                extBayReplicaRepository,
                extMobileUnitReplicaRepository,
                extWorkorderReplicaRepository,
                extVehicleReplicaRepository,
                extPersonReplicaRepository,
                mechanicRepository);
        when(extBayReplicaRepository.findActiveByLocationOrdered(any())).thenReturn(List.of());
        when(extMobileUnitReplicaRepository.findActiveByBaseLocationOrdered(any()))
                .thenReturn(List.of());
        when(extWorkorderReplicaRepository.findOpenAtLocation(any(), any(), any()))
                .thenReturn(List.of());
        when(extVehicleReplicaRepository.findAllById(any())).thenReturn(List.of());
        when(extPersonReplicaRepository.findAllById(any())).thenReturn(List.of());
        when(mechanicRepository.findAllByPersonIdIn(anyList())).thenReturn(List.of());
    }

    @Test
    @DisplayName("#1658 AC2 - an omitted date resolves to the shop's own calendar day, not the server's")
    void defaultDateUsesTheShopTimezone() {
        givenShopIn("America/New_York");

        assertThat(service.getDashboard(locationId, null).date()).isEqualTo(LocalDate.of(2026, 9, 3));
    }

    @Test
    @DisplayName("#1658 AC2 - a shop east of UTC gets its own later day, proving the server zone is not used")
    void defaultDateFollowsEasternTimezonesToo() {
        givenShopIn("Europe/Paris");

        assertThat(service.getDashboard(locationId, null).date()).isEqualTo(LocalDate.of(2026, 9, 4));
    }

    @Test
    @DisplayName("#1658 AC2 - a shop with no recorded timezone falls back to UTC")
    void missingTimezoneFallsBackToUtc() {
        givenShopIn(null);

        assertThat(service.getDashboard(locationId, null).date()).isEqualTo(LocalDate.of(2026, 9, 4));
    }

    @Test
    @DisplayName("#1658 AC2 - an unrecognised timezone falls back to UTC instead of failing the read")
    void unparseableTimezoneFallsBackToUtc() {
        givenShopIn("Mars/Olympus_Mons");

        assertThat(service.getDashboard(locationId, null).date()).isEqualTo(LocalDate.of(2026, 9, 4));
    }

    @Test
    @DisplayName("#1658 AC2 - an explicit date is honoured verbatim")
    void explicitDateWins() {
        givenShopIn("America/New_York");

        assertThat(service.getDashboard(locationId, LocalDate.of(2026, 1, 15)).date())
                .isEqualTo(LocalDate.of(2026, 1, 15));
    }

    private void givenShopIn(String timezone) {
        when(shopRepository.findById(locationId))
                .thenReturn(Optional.of(Shop.builder()
                        .id(locationId)
                        .name("Shop")
                        .timezone(timezone)
                        .build()));
    }
}
