package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.client.TaxProfileClient;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** One profile read per country per minute, and one timeout per burst when pos-tax is down (#2679 review). */
@DisplayName("TaxProfileReads (#2679)")
class TaxProfileReadsTest {

    private static final TaxProfileClient.TaxTypes PROFILE =
            new TaxProfileClient.TaxTypes("ZZ", "XTS", List.of(), List.of());

    private final TaxProfileClient client = mock(TaxProfileClient.class);
    private Instant now = Instant.parse("2026-10-16T12:00:00Z");
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };
    private final TaxProfileReads reads = new TaxProfileReads(client, clock);

    @Test
    @DisplayName("a fresh answer is served from the cache; it is asked again once it has aged")
    void cachesWhileFresh() {
        when(client.taxTypes("ZZ")).thenReturn(PROFILE);

        assertThat(reads.taxTypesIfAvailable("ZZ")).contains(PROFILE);
        assertThat(reads.taxTypesIfAvailable("ZZ")).contains(PROFILE);
        verify(client, times(1)).taxTypes("ZZ");

        now = now.plus(TaxProfileReads.FRESH_FOR).plus(Duration.ofSeconds(1));
        assertThat(reads.taxTypesIfAvailable("ZZ")).contains(PROFILE);
        verify(client, times(2)).taxTypes("ZZ");
    }

    @Test
    @DisplayName("a miss is empty and remembered briefly, so a burst of reads pays one timeout")
    void remembersAMissBriefly() {
        when(client.taxTypes("ZZ")).thenThrow(new TaxServiceUnavailableException("down"));

        assertThat(reads.taxTypesIfAvailable("ZZ")).isEmpty();
        assertThat(reads.taxTypesIfAvailable("ZZ")).isEmpty();
        verify(client, times(1)).taxTypes("ZZ");

        now = now.plus(TaxProfileReads.MISS_REMEMBERED_FOR).plus(Duration.ofSeconds(1));
        assertThat(reads.taxTypesIfAvailable("ZZ")).isEmpty();
        verify(client, times(2)).taxTypes("ZZ");
    }
}
