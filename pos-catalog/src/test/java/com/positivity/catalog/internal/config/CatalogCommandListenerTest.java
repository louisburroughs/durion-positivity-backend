package com.positivity.catalog.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.dto.ProductFactReplayResultDto;
import com.positivity.catalog.internal.dto.ServiceFactReplayResultDto;
import com.positivity.catalog.internal.dto.SupplierArticleCodeReplayResultDto;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.service.ProductFactReplayService;
import com.positivity.catalog.service.ServiceFactReplayService;
import com.positivity.catalog.service.SupplierArticleCodeReplayService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

/**
 * Unit tests for {@link CatalogCommandListener} (ADR-0044 §4, #1537).
 */
class CatalogCommandListenerTest {

    private final ProductFactReplayService productFactReplayService = mock(ProductFactReplayService.class);
    private final ServiceFactReplayService serviceFactReplayService = mock(ServiceFactReplayService.class);
    private final SupplierArticleCodeReplayService supplierArticleCodeReplayService =
            mock(SupplierArticleCodeReplayService.class);

    private CatalogCommandListener listener;

    @BeforeEach
    void setUp() {
        listener = new CatalogCommandListener(
                new tools.jackson.databind.ObjectMapper(),
                productFactReplayService,
                serviceFactReplayService,
                supplierArticleCodeReplayService);

        when(productFactReplayService.replayPage(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new ProductFactReplayResultDto(0, null, true, null, Instant.now()));
        when(serviceFactReplayService.replayPage(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new ServiceFactReplayResultDto(0, null, true, null, Instant.now()));
        when(supplierArticleCodeReplayService.replayPage(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new SupplierArticleCodeReplayResultDto(0, null, true, null, Instant.now()));
    }

    private String replayCommand(String since, String until) {
        return """
                {"commandType":"catalog.outbox.replay-requested",
                 "payload":{"since":"%s","until":"%s"}}
                """.formatted(since, until);
    }

    @Test
    @DisplayName("Replay command with no scope dispatches to all three replay services")
    void dispatchesToAllThreeByDefault() {
        listener.onCommand(replayCommand("2026-07-13T10:00:00Z", "2026-07-13T11:00:00Z"));

        verify(productFactReplayService)
                .replayPage(
                        isNull(),
                        eq(Instant.parse("2026-07-13T10:00:00Z")),
                        eq(CatalogCommandListener.MAX_REPLAY_LIMIT));
        verify(serviceFactReplayService)
                .replayPage(
                        isNull(),
                        eq(Instant.parse("2026-07-13T10:00:00Z")),
                        eq(CatalogCommandListener.MAX_REPLAY_LIMIT));
        verify(supplierArticleCodeReplayService)
                .replayPage(
                        isNull(),
                        eq(Instant.parse("2026-07-13T10:00:00Z")),
                        eq(CatalogCommandListener.MAX_REPLAY_LIMIT));
    }

    @Test
    @DisplayName("scope=PRODUCT dispatches only to the product replay service")
    void scopedToProduct() {
        listener.onCommand("""
                {"commandType":"CATALOG_OUTBOX_REPLAY_REQUESTED",
                 "payload":{"since":"2026-07-13T10:00:00Z","scope":"product"}}
                """);

        verify(productFactReplayService)
                .replayPage(isNull(), eq(Instant.parse("2026-07-13T10:00:00Z")), any(Integer.class));
        verifyNoInteractions(serviceFactReplayService);
        verifyNoInteractions(supplierArticleCodeReplayService);
    }

    @Test
    @DisplayName("scope=SERVICE dispatches only to the service replay service")
    void scopedToService() {
        listener.onCommand("""
                {"commandType":"catalog.outbox.replay-requested",
                 "payload":{"scope":"SERVICE"}}
                """);

        verifyNoInteractions(productFactReplayService);
        verify(serviceFactReplayService).replayPage(isNull(), isNull(), any(Integer.class));
        verifyNoInteractions(supplierArticleCodeReplayService);
    }

    @Test
    @DisplayName("scope=SUPPLIER_ARTICLE_CODE dispatches only to the supplier-article-code replay service")
    void scopedToSupplierArticleCode() {
        listener.onCommand("""
                {"commandType":"catalog.outbox.replay-requested",
                 "payload":{"scope":"SUPPLIER_ARTICLE_CODE"}}
                """);

        verifyNoInteractions(productFactReplayService);
        verifyNoInteractions(serviceFactReplayService);
        verify(supplierArticleCodeReplayService).replayPage(isNull(), isNull(), any(Integer.class));
    }

    @Test
    @DisplayName("Unrecognized scope is logged and dropped without calling any replay service")
    void unrecognizedScopeIsDropped() {
        listener.onCommand("""
                {"commandType":"catalog.outbox.replay-requested",
                 "payload":{"scope":"BOGUS"}}
                """);

        verifyNoInteractions(productFactReplayService);
        verifyNoInteractions(serviceFactReplayService);
        verifyNoInteractions(supplierArticleCodeReplayService);
    }

    @Test
    @DisplayName("Missing since replays from the beginning (updatedSince=null)")
    void missingSinceReplaysAll() {
        listener.onCommand("""
                {"commandType":"catalog.outbox.replay-requested","payload":{}}
                """);

        verify(productFactReplayService).replayPage(isNull(), isNull(), any(Integer.class));
        verify(serviceFactReplayService).replayPage(isNull(), isNull(), any(Integer.class));
        verify(supplierArticleCodeReplayService).replayPage(isNull(), isNull(), any(Integer.class));
    }

    @Test
    @DisplayName("An afterProductId cursor in the payload is passed through to the product replay call")
    void honorsAfterProductIdCursor() {
        UUID cursor = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
        listener.onCommand("""
                {"commandType":"catalog.outbox.replay-requested",
                 "payload":{"scope":"PRODUCT","afterProductId":"%s"}}
                """.formatted(cursor));

        verify(productFactReplayService).replayPage(eq(cursor), isNull(), any(Integer.class));
    }

    @Test
    @DisplayName("A payload limit above MAX_REPLAY_LIMIT is clamped down")
    void clampsExcessiveLimit() {
        listener.onCommand("""
                {"commandType":"catalog.outbox.replay-requested","payload":{"limit":999999}}
                """);

        verify(productFactReplayService).replayPage(any(), any(), eq(CatalogCommandListener.MAX_REPLAY_LIMIT));
    }

    @Test
    @DisplayName("A publication-disabled refusal from one scope is logged and does not block the others")
    void publicationDisabledOnOneScopeDoesNotBlockOthers() {
        when(productFactReplayService.replayPage(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new CatalogBusinessRuleException("Fact publication is disabled"));

        assertThatCode(() -> listener.onCommand(replayCommand("2026-07-13T10:00:00Z", "2026-07-13T11:00:00Z")))
                .doesNotThrowAnyException();

        verify(serviceFactReplayService).replayPage(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(supplierArticleCodeReplayService).replayPage(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("Malformed commands are logged and dropped, not rethrown")
    void dropsMalformedCommands() {
        assertThatCode(() -> listener.onCommand("{ not json ")).doesNotThrowAnyException();
        assertThatCode(() -> listener.onCommand("{\"commandType\":\"something.else\"}"))
                .doesNotThrowAnyException();
        verifyNoInteractions(productFactReplayService);
        verifyNoInteractions(serviceFactReplayService);
        verifyNoInteractions(supplierArticleCodeReplayService);
    }

    @Test
    @DisplayName("Transient DB errors rethrow for container retry/DLQ (ADR-0044 §4)")
    void rethrowsTransientErrors() {
        when(productFactReplayService.replayPage(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new QueryTimeoutException("db timeout"));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onCommand(replayCommand("2026-07-13T10:00:00Z", "2026-07-13T11:00:00Z")));
    }

    @Test
    @DisplayName("Kafka disabled: without a component (ConditionalOnProperty false) no listener bean exists,"
            + " so onCommand is never invoked in that profile — verified via the disabled refusal path instead")
    void kafkaDisabledSurfacesAsPublicationDisabledRefusal() {
        // pos.catalog.kafka.enabled=false means CatalogFactPublisher.publicationEnabled() is false,
        // which every replay service surfaces as CatalogBusinessRuleException — already covered by
        // publicationDisabledOnOneScopeDoesNotBlockOthers. This test documents that this listener
        // itself carries no separate enabled/disabled branch: it is entirely gated out of the
        // Spring context by @ConditionalOnProperty when Kafka is off (mirroring LocationCommandListener),
        // and delegates the "would emit nothing" refusal to the replay services it calls.
        assertThat(CatalogCommandListener.class.getAnnotation(
                        org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class))
                .isNotNull();
    }
}
