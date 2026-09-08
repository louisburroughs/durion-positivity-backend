package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.repository.PickTaskRepository;
import com.positivity.inventory.internal.repository.ReservationRepository;
import com.positivity.inventory.internal.service.ForecastQuantityService.ForecastQuantities;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ForecastQuantityServiceImpl#forecastWithin}: the reach-narrowed forecast (ADR-0061 §3,
 * #1872) sums site-bound supply and pick demand per site, counts SKU-wide reservation demand once,
 * and forecasts nothing for an empty reach.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ForecastQuantityServiceImpl.forecastWithin")
class ForecastQuantityServiceWithinTest {

    private static final UUID SKU = UUID.fromString("00000000-0000-0000-0000-000000000123");
    private static final UUID SITE_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID SITE_B = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    @Mock
    private ExpectedSupplyService expectedSupplyService;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private PickTaskRepository pickTaskRepository;

    @InjectMocks
    private ForecastQuantityServiceImpl service;

    @Test
    @DisplayName("two reachable sites: supply and pick demand summed per site, reservations once")
    void sumsPerSiteAndCountsReservationsOnce() {
        String sku = SKU.toString();
        when(expectedSupplyService.expectedIncomingQuantity(sku, SITE_A, null)).thenReturn(new BigDecimal("3"));
        when(expectedSupplyService.expectedIncomingQuantity(sku, SITE_B, null)).thenReturn(new BigDecimal("4"));
        when(pickTaskRepository.sumReleasedUnpickedRemainderForSku(eq(sku), eq(SKU), any(), anyList(), eq(SITE_A)))
                .thenReturn(1L);
        when(pickTaskRepository.sumReleasedUnpickedRemainderForSku(eq(sku), eq(SKU), any(), anyList(), eq(SITE_B)))
                .thenReturn(2L);
        when(reservationRepository.sumOpenRemainderForSku(eq(SKU), anyList(), anyBoolean(), any()))
                .thenReturn(new BigDecimal("5"));

        ForecastQuantities forecast = service.forecastWithin(sku, Set.of(SITE_A, SITE_B), null, new BigDecimal("10"));

        assertThat(forecast.incomingQty()).isEqualByComparingTo("7");
        // 5 (reservations, once) + 1 + 2 (pick demand per site)
        assertThat(forecast.outgoingQty()).isEqualByComparingTo("8");
        assertThat(forecast.projectedAvailable()).isEqualByComparingTo("9");
    }

    @Test
    @DisplayName("empty reach: nothing incoming or outgoing, projected is the (zero) on-hand")
    void emptyReachForecastsNothing() {
        ForecastQuantities forecast = service.forecastWithin(SKU.toString(), Set.of(), null, BigDecimal.ZERO);

        assertThat(forecast.incomingQty()).isEqualByComparingTo("0");
        assertThat(forecast.outgoingQty()).isEqualByComparingTo("0");
        assertThat(forecast.projectedAvailable()).isEqualByComparingTo("0");
        verifyNoInteractions(expectedSupplyService, reservationRepository, pickTaskRepository);
    }
}
