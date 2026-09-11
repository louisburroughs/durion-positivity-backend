package com.positivity.poseventreceiver.internal.service;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.poseventreceiver.internal.dto.EventSummaryResponse;
import com.positivity.poseventreceiver.internal.exception.TenantScopeForbiddenException;
import com.positivity.poseventreceiver.internal.repository.EmittedEventHourlyRepository;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantContextMissingException;
import com.positivity.tenancy.TenantResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The tenant dimension of the hourly statistics (ADR-0062 plan WS6): the aggregate has no
 * row-level security, so the scope this service picks is the isolation. Bound tenants read
 * themselves; the platform tenant reads the global rollup or one named tenant; naming a tenant
 * from anywhere else is refused.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventSummaryServiceImpl — tenant scope of the hourly statistics")
class EventSummaryServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-04-11T12:00:00Z");

    @Mock
    private EmittedEventHourlyRepository emittedEventHourlyRepository;

    private EventSummaryServiceImpl service;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        // Strict resolver: no default tenant, so only what the test binds counts.
        service = new EventSummaryServiceImpl(
                emittedEventHourlyRepository, fixedClock, new TenantResolver(new TenancyProperties()));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static List<Object[]> rows(Object[]... eventTypeAndCount) {
        return List.of(eventTypeAndCount);
    }

    @Nested
    @DisplayName("a caller bound to an ordinary tenant")
    class BoundTenant {

        @Test
        void readsItsOwnTenantAndMapsTheRows() {
            when(emittedEventHourlyRepository.summarizeSince(eq(TENANT_A), any(Instant.class)))
                    .thenReturn(rows(new Object[] {"ORDER_ORDER_CREATE", Long.valueOf(5)}));

            List<EventSummaryResponse> result = asTenant(TENANT_A, () -> service.getLastHourSummary(null));

            assertThat(result).containsExactly(new EventSummaryResponse("ORDER_ORDER_CREATE", 5L));
            verify(emittedEventHourlyRepository).summarizeSince(TENANT_A, NOW.minusSeconds(3600));
            verify(emittedEventHourlyRepository, never()).summarizeAcrossTenantsSince(any());
        }

        @Test
        void mayNotNameAnotherTenant() {
            assertThatThrownBy(() -> asTenant(TENANT_A, () -> service.getLastDaySummary(TENANT_B)))
                    .isInstanceOf(TenantScopeForbiddenException.class);
            verifyNoInteractions(emittedEventHourlyRepository);
        }

        @Test
        void mayNotNameEvenItself() {
            // tenantId is a platform-tenant parameter, not a way to spell "me".
            assertThatThrownBy(() -> asTenant(TENANT_A, () -> service.getLastWeekSummary(TENANT_A)))
                    .isInstanceOf(TenantScopeForbiddenException.class);
            verifyNoInteractions(emittedEventHourlyRepository);
        }
    }

    @Nested
    @DisplayName("a caller bound to the platform tenant")
    class PlatformTenantCaller {

        @Test
        void readsTheGlobalRollupByDefault() {
            when(emittedEventHourlyRepository.summarizeAcrossTenantsSince(any(Instant.class)))
                    .thenReturn(rows(new Object[] {"INVENTORY_ADJUST", Long.valueOf(12)}));

            List<EventSummaryResponse> result = asTenant(PlatformTenant.ID, () -> service.getLastDaySummary(null));

            assertThat(result).containsExactly(new EventSummaryResponse("INVENTORY_ADJUST", 12L));
            verify(emittedEventHourlyRepository).summarizeAcrossTenantsSince(Instant.parse("2026-04-10T12:00:00Z"));
            verify(emittedEventHourlyRepository, never()).summarizeSince(any(), any());
        }

        @Test
        void readsOneTenantWhenItNamesIt() {
            when(emittedEventHourlyRepository.summarizeSince(eq(TENANT_B), any(Instant.class)))
                    .thenReturn(rows(new Object[] {"WORKORDER_ESTIMATE_APPROVE", Integer.valueOf(9)}));

            List<EventSummaryResponse> result = asTenant(PlatformTenant.ID, () -> service.getLastWeekSummary(TENANT_B));

            assertThat(result)
                    .as("a non-Long aggregate is mapped safely")
                    .containsExactly(new EventSummaryResponse("WORKORDER_ESTIMATE_APPROVE", 9L));
            verify(emittedEventHourlyRepository).summarizeSince(TENANT_B, Instant.parse("2026-04-04T12:00:00Z"));
            verify(emittedEventHourlyRepository, never()).summarizeAcrossTenantsSince(any());
        }

        @Test
        void mayNameItselfToSeeOnlyPlatformEvents() {
            UUID platform = PlatformTenant.ID;
            when(emittedEventHourlyRepository.summarizeSince(eq(platform), any(Instant.class)))
                    .thenReturn(rows());

            assertThat(asTenant(platform, () -> service.getLastHourSummary(platform)))
                    .isEmpty();
            verify(emittedEventHourlyRepository).summarizeSince(platform, NOW.minusSeconds(3600));
        }
    }

    @Test
    void anUnboundCallerIsRefusedBeforeAnyQuery() {
        assertThatThrownBy(() -> service.getLastHourSummary(null))
                .as("strict tenancy: nothing bound, nothing read")
                .isInstanceOf(TenantContextMissingException.class);
        verifyNoInteractions(emittedEventHourlyRepository);
    }
}
