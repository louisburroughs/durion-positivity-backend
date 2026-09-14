package com.positivity.bulkloader.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.bulkloader.internal.enums.DomainType;
import com.positivity.bulkloader.internal.service.BulkIngestResultRecorder;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

/**
 * Pins how the ingest writer builds its client.
 *
 * <p>A chunk POST and a single-key lookup share one load-balanced {@code RestClient.Builder} but
 * want opposite timeouts. The lookup keeps 5s so a hung sibling cannot hold a worker thread; the
 * POST asks the owning service to validate, persist and emit an event for up to a chunk of rows,
 * and 5s is nowhere near enough. On alpha the 329-row vehicle chunk hit that limit after the
 * service had already committed all 329: Spring Batch rolled the chunk back, re-sent every row
 * individually, collected 329 "already exists" rejections, and reported success=0 for a load that
 * had landed in full. The 501-row product chunk did the same. A timeout here does not fail safe, it
 * reports the opposite of what happened — which is why the writer must configure its own.
 *
 * <p>The clone matters as much as the timeout: the builder is shared with every other writer and
 * with resolution, so configuring the original would hand one service's base URI and write timeout
 * to the next client built from it.
 */
class BulkIngestWriterTimeoutTest {

    private static final BulkIngestWriterFactory.Target TARGET = new BulkIngestWriterFactory.Target(
            "testBulkIngestWriter", DomainType.CATALOG_PRODUCT, "test-service", "/v1/test/bulk-ingest", "test:ingest");

    @Test
    @DisplayName("the writer clones the shared builder and gives the clone its own request factory")
    void writer_clonesTheBuilderAndSetsItsOwnRequestFactory() {
        RestClient.Builder shared = mock(RestClient.Builder.class);
        RestClient.Builder cloned = mock(RestClient.Builder.class);
        RestClient client = mock(RestClient.class);
        when(shared.clone()).thenReturn(cloned);
        when(cloned.requestFactory(any(ClientHttpRequestFactory.class))).thenReturn(cloned);
        when(cloned.baseUrl(anyString())).thenReturn(cloned);
        when(cloned.build()).thenReturn(client);

        BulkIngestWriterFactory factory =
                new BulkIngestWriterFactory(mock(AuthorizationHeaderRelay.class), mock(BulkIngestResultRecorder.class));
        ReflectionTestUtils.setField(factory, "ingestReadTimeoutMs", 120_000);
        ReflectionTestUtils.setField(factory, "connectTimeoutMs", 2_000);

        factory.create(
                shared,
                TARGET,
                new BulkIngestWriterFactory.JobParams(UUID.randomUUID().toString(), "loc", "op"));

        // The shared builder itself is never configured — only the clone.
        verify(shared).clone();
        ArgumentCaptor<ClientHttpRequestFactory> captured = ArgumentCaptor.captor();
        verify(cloned).requestFactory(captured.capture());
        assertThat(captured.getValue()).isNotNull();
        // SimpleClientHttpRequestFactory stores the timeout as milliseconds, not a Duration.
        assertThat(ReflectionTestUtils.getField(captured.getValue(), "readTimeout"))
                .as("the write client must not inherit the 5s lookup timeout")
                .isEqualTo(120_000);
    }
}
