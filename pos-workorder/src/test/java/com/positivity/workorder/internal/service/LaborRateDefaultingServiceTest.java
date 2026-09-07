package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.client.PriceLaborRateClient;
import com.positivity.workorder.internal.entity.ExtCatalogServiceReplica;
import com.positivity.workorder.internal.repository.ExtCatalogServiceReplicaRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rate lookup for LABOR lines (#1575 Tier 0, #1569 R4): the operation category comes from the
 * local catalog replica rather than a second cross-module call, and every miss is fail-soft.
 */
@DisplayName("LaborRateDefaultingService")
class LaborRateDefaultingServiceTest {

    private static final UUID SERVICE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fdd01");
    private static final UUID LOCATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fdd02");
    private static final UUID RATE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fdd03");

    private PriceLaborRateClient client;
    private ExtCatalogServiceReplicaRepository replicaRepository;
    private LaborRateDefaultingService service;

    @BeforeEach
    void setUp() {
        client = mock(PriceLaborRateClient.class);
        replicaRepository = mock(ExtCatalogServiceReplicaRepository.class);
        service = new LaborRateDefaultingService(client, replicaRepository);
        when(replicaRepository.findById(any())).thenReturn(Optional.empty());
        when(client.resolveLaborRate(any(), any(), any())).thenReturn(Optional.empty());
    }

    private static ExtCatalogServiceReplica replicaWithCategory(String category) {
        return ExtCatalogServiceReplica.builder()
                .serviceId(SERVICE_ID)
                .operationCategory(category)
                .active(true)
                .build();
    }

    private static PriceLaborRateClient.LaborRate rate() {
        return new PriceLaborRateClient.LaborRate(
                new BigDecimal("120.7500"),
                new BigDecimal("105.0000"),
                "USD",
                "LOCATION_CATEGORY",
                RATE_ID,
                List.of("CORROSION"));
    }

    @Test
    @DisplayName("the operation category comes from the local replica, not a second call to pos-catalog")
    void categoryComesFromTheReplica() {
        when(replicaRepository.findById(SERVICE_ID)).thenReturn(Optional.of(replicaWithCategory("TIRE_SERVICE")));

        service.lookupLaborRate(SERVICE_ID, LOCATION_ID, List.of("CORROSION"));

        verify(client).resolveLaborRate(eq(LOCATION_ID), eq("TIRE_SERVICE"), eq(List.of("CORROSION")));
    }

    @Test
    @DisplayName("a service the replica has not seen resolves the category-agnostic rate rather than failing")
    void unknownServiceWidensToCategoryAgnostic() {
        service.lookupLaborRate(SERVICE_ID, LOCATION_ID, List.of());

        verify(client).resolveLaborRate(eq(LOCATION_ID), eq(null), eq(List.of()));
    }

    @Test
    @DisplayName("a resolved rate carries its provenance, with applied matrix codes comma-joined")
    void resolvedRateCarriesProvenance() {
        when(client.resolveLaborRate(any(), any(), any())).thenReturn(Optional.of(rate()));

        LaborRateDefaultingService.RateDefault resolved =
                service.lookupLaborRate(SERVICE_ID, LOCATION_ID, List.of()).orElseThrow();

        assertThat(resolved.hourlyRate()).isEqualByComparingTo("120.7500");
        assertThat(resolved.baseHourlyRate()).isEqualByComparingTo("105.0000");
        assertThat(resolved.currency()).isEqualTo("USD");
        assertThat(resolved.scope()).isEqualTo("LOCATION_CATEGORY");
        assertThat(resolved.rateId()).isEqualTo(RATE_ID);
        assertThat(resolved.appliedCodes()).isEqualTo("CORROSION");
    }

    @Test
    @DisplayName("no applied matrix codes stores null rather than an empty string")
    void noAppliedCodesIsNull() {
        when(client.resolveLaborRate(any(), any(), any()))
                .thenReturn(Optional.of(new PriceLaborRateClient.LaborRate(
                        new BigDecimal("105.0000"), null, "USD", "LOCATION_DEFAULT", RATE_ID, List.of())));

        assertThat(service.lookupLaborRate(SERVICE_ID, LOCATION_ID, null)
                        .orElseThrow()
                        .appliedCodes())
                .isNull();
    }

    @Test
    @DisplayName("an unreachable price edge is an empty answer — the writer types the price")
    void edgeMissIsEmpty() {
        assertThat(service.lookupLaborRate(SERVICE_ID, LOCATION_ID, List.of())).isEmpty();
    }

    @Test
    @DisplayName("a null serviceId skips the replica read entirely and still asks for a rate")
    void nullServiceIdStillAsksForARate() {
        service.lookupLaborRate(null, LOCATION_ID, List.of());

        verify(replicaRepository, org.mockito.Mockito.never()).findById(any());
        verify(client).resolveLaborRate(eq(LOCATION_ID), eq(null), eq(List.of()));
    }
}
