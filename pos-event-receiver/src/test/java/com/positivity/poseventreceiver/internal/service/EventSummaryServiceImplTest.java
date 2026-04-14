package com.positivity.poseventreceiver.internal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.poseventreceiver.internal.dto.EventSummaryResponse;
import com.positivity.poseventreceiver.internal.repository.EmittedEventHourlyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventSummaryServiceImplTest {

    @Mock
    private EmittedEventHourlyRepository emittedEventHourlyRepository;

    private EventSummaryServiceImpl service;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-04-11T12:00:00Z"), ZoneOffset.UTC);
        service = new EventSummaryServiceImpl(emittedEventHourlyRepository, fixedClock);
    }

    @Test
    void getLastHourSummary_usesAggregateRepositoryAndMapsResponse() {
        when(emittedEventHourlyRepository.summarizeSince(any(Instant.class)))
                .thenReturn(List.<Object[]>of(new Object[] {"ORDER_ORDER_CREATE", Long.valueOf(5)}));

        List<EventSummaryResponse> result = service.getLastHourSummary();

        assertEquals(1, result.size());
        assertEquals("ORDER_ORDER_CREATE", result.get(0).eventTypeId());
        assertEquals(5L, result.get(0).count());
        verify(emittedEventHourlyRepository).summarizeSince(Instant.parse("2026-04-11T11:00:00Z"));
    }

    @Test
    void getLastDaySummary_usesAggregateRepositoryWithExpectedWindow() {
        when(emittedEventHourlyRepository.summarizeSince(any(Instant.class)))
                .thenReturn(List.<Object[]>of(new Object[] {"INVENTORY_ADJUST", Long.valueOf(12)}));

        service.getLastDaySummary();

        verify(emittedEventHourlyRepository).summarizeSince(Instant.parse("2026-04-10T12:00:00Z"));
    }

    @Test
    void getLastWeekSummary_mapsNumericAggregateSafely() {
        when(emittedEventHourlyRepository.summarizeSince(any(Instant.class)))
                .thenReturn(List.<Object[]>of(new Object[] {"WORKORDER_ESTIMATE_APPROVE", Integer.valueOf(9)}));

        List<EventSummaryResponse> result = service.getLastWeekSummary();

        assertEquals(9L, result.get(0).count());
        verify(emittedEventHourlyRepository).summarizeSince(Instant.parse("2026-04-04T12:00:00Z"));
    }
}
