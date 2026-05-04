package com.positivity.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.inventory.internal.client.ExternalAvailabilityClient;
import com.positivity.inventory.internal.client.ProductSubstituteClient;
import com.positivity.inventory.internal.dto.ShortageOptionDto;
import com.positivity.inventory.internal.dto.ShortageResolveRequest;
import com.positivity.inventory.internal.service.ShortageResolutionServiceImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("ShortageResolutionServiceImpl")
class ShortageResolutionServiceImplTest {

        private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

        @Test
        @DisplayName("listShortageOptions returns deterministic options for allocation")
        void listShortageOptions_returnsExpectedOptions() {
                ShortageResolutionServiceImpl service = new ShortageResolutionServiceImpl(
                                Mockito.mock(ProductSubstituteClient.class),
                                Mockito.mock(ExternalAvailabilityClient.class),
                                Runnable::run,
                                TEST_CLOCK);

                UUID allocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                var options = service.listShortageOptions(allocationId);

                assertThat(options).hasSize(2);
                assertThat(options)
                                .extracting(ShortageOptionDto::getAllocationId)
                                .containsOnly(allocationId);
                assertThat(options)
                                .extracting(ShortageOptionDto::getResolution)
                                .containsExactly("BACKORDER", "CANCEL");
        }

        @Test
        @DisplayName("resolveShortage maps request into resolved result")
        void resolveShortage_returnsResolutionResult() {
                ShortageResolutionServiceImpl service = new ShortageResolutionServiceImpl(
                                Mockito.mock(ProductSubstituteClient.class),
                                Mockito.mock(ExternalAvailabilityClient.class),
                                Runnable::run,
                                TEST_CLOCK);

                UUID allocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                ShortageResolveRequest request = ShortageResolveRequest.builder()
                                .allocationId(allocationId)
                                .resolution("BACKORDER")
                                .build();

                var result = service.resolveShortage(request);

                assertThat(result.getAllocationId()).isEqualTo(allocationId);
                assertThat(result.getResolution()).isEqualTo("BACKORDER");
                assertThat(result.getStatus()).isEqualTo("RESOLVED");
                assertThat(result.getResolvedAt()).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
        }
}
