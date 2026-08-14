package com.positivity.supplier.internal.stockreport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.supplier.internal.adapter.ediwheelb.EdiwheelB21StockReportCodec;
import com.positivity.supplier.internal.client.SupplierBaseClient;
import com.positivity.supplier.internal.client.SupplierHttpRequest;
import com.positivity.supplier.internal.client.SupplierHttpResponse;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.entity.StockSnapshotEntity;
import com.positivity.supplier.internal.entity.SupplierAccountEntity;
import com.positivity.supplier.internal.entity.SupplierAuthConfigEntity;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.registry.AdapterRegistry;
import com.positivity.supplier.internal.registry.AdapterResolution;
import com.positivity.supplier.internal.service.SupplierProfileResolver;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedPartyAccounts;
import com.positivity.supplier.internal.spi.ExchangeOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Stock report fetch orchestration (#1228)")
class StockReportImporterTest {

    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");
    private static final SupplierRef SUPPLIER = new SupplierRef("michelin-eu");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-14T09:00:00Z"), ZoneOffset.UTC);

    private static final String GOOD_DOCUMENT = """
            {"envelopeHeader":{"issueDate":"20260814","issueTime":"0600","documentId":"D1","variant":"B2_1",
              "errorCode":"0"},
             "buyerParty":"30012456",
             "lineLevel":[{"lineId":"1","article":{"articleIdentification":{"eanuccarticleID":"3528709999083"},
               "availableQuantity":{"quantityValue":"42"}}}]}
            """;

    @Mock
    private SupplierProfileResolver profileResolver;

    @Mock
    private AdapterRegistry adapterRegistry;

    @Mock
    private SupplierBaseClient baseClient;

    @Mock
    private StockReportSnapshotWriter snapshotWriter;

    private StockReportImporter importer;

    @BeforeEach
    void setUp() {
        importer = new StockReportImporter(profileResolver, adapterRegistry, baseClient, snapshotWriter, CLOCK);
        ReflectionTestUtils.setField(importer, "defaultChunkSize", 500);
        ReflectionTestUtils.setField(importer, "sendBuyerParty", false);

        when(profileResolver.resolveBinding(SUPPLIER, SupplierCapability.STOCK_REPORT))
                .thenReturn(binding(null));
        when(profileResolver.resolvePartyContext(SUPPLIER, null))
                .thenReturn(new ResolvedPartyAccounts(billing(), null));
        when(adapterRegistry.resolve(SupplierCapability.STOCK_REPORT, ProtocolFamily.EDIWHEEL_B, ProtocolVersion.B2_1))
                .thenReturn(new AdapterResolution.Resolved(new EdiwheelB21StockReportCodec(new ObjectMapper())));
        when(snapshotWriter.commit(any(), any(), any(), anyString(), any(), anyInt()))
                .thenReturn(StockSnapshotEntity.builder().build());
        when(snapshotWriter.persistFailure(any(), any(), anyString(), any(), any()))
                .thenReturn(StockSnapshotEntity.builder().build());
    }

    private static ResolvedBinding binding(Integer chunkSizeOverride) {
        SupplierProfileEntity profile = new SupplierProfileEntity();
        profile.setVendorProfileId(PROFILE_ID);
        profile.setSupplierRef(SUPPLIER.value());
        profile.setEnabled(true);

        SupplierEndpointBindingEntity endpointBinding = new SupplierEndpointBindingEntity();
        endpointBinding.setId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5e"));
        endpointBinding.setVendorProfileId(PROFILE_ID);
        endpointBinding.setCapability(SupplierCapability.STOCK_REPORT);
        endpointBinding.setProtocolFamily(ProtocolFamily.EDIWHEEL_B);
        endpointBinding.setProtocolVersion("B2_1");
        endpointBinding.setEnabled(true);
        endpointBinding.setEventChunkSize(chunkSizeOverride);

        return new ResolvedBinding(
                profile,
                endpointBinding,
                new SupplierAuthConfigEntity(),
                SupplierCapability.STOCK_REPORT,
                ProtocolFamily.EDIWHEEL_B,
                ProtocolVersion.B2_1);
    }

    private static SupplierAccountEntity billing() {
        SupplierAccountEntity account = new SupplierAccountEntity();
        account.setAccountNumber("30012456");
        account.setAgencyCode("91");
        return account;
    }

    private static SupplierHttpResponse response(ExchangeOutcome outcome, String body) {
        return new SupplierHttpResponse(
                outcome,
                outcome.isSuccess() ? 200 : 503,
                body,
                "corr-1",
                1,
                Duration.ofSeconds(1),
                outcome.isSuccess() ? null : "vendor unavailable");
    }

    @Test
    void commitsTheSnapshotOnASuccessfulFetch() {
        when(baseClient.exchange(any(SupplierHttpRequest.class)))
                .thenReturn(response(ExchangeOutcome.OK, GOOD_DOCUMENT));

        importer.runSnapshot(SUPPLIER);

        verify(snapshotWriter).commit(any(), any(), any(), anyString(), any(), eq(500));
        verify(snapshotWriter, never()).persistFailure(any(), any(), anyString(), any(), any());
    }

    @Test
    void sendsNoQueryParametersUnlessTheBindingIsAC10Endpoint() {
        when(baseClient.exchange(any(SupplierHttpRequest.class)))
                .thenReturn(response(ExchangeOutcome.OK, GOOD_DOCUMENT));

        importer.runSnapshot(SUPPLIER);

        ArgumentCaptor<SupplierHttpRequest> captor = ArgumentCaptor.forClass(SupplierHttpRequest.class);
        verify(baseClient).exchange(captor.capture());
        assertThat(captor.getValue().queryParams()).isEmpty();
        assertThat(captor.getValue().idempotent()).isTrue();
    }

    @Test
    void sendsBuyerIdentificationWhenConfiguredForAC10Endpoint() {
        ReflectionTestUtils.setField(importer, "sendBuyerParty", true);
        when(baseClient.exchange(any(SupplierHttpRequest.class)))
                .thenReturn(response(ExchangeOutcome.OK, GOOD_DOCUMENT));

        importer.runSnapshot(SUPPLIER);

        ArgumentCaptor<SupplierHttpRequest> captor = ArgumentCaptor.forClass(SupplierHttpRequest.class);
        verify(baseClient).exchange(captor.capture());
        assertThat(captor.getValue().queryParams()).containsEntry("buyerParty", "30012456");
    }

    @Test
    void prefersTheBindingsChunkSizeOverTheDeploymentDefault() {
        when(profileResolver.resolveBinding(SUPPLIER, SupplierCapability.STOCK_REPORT))
                .thenReturn(binding(120));
        when(baseClient.exchange(any(SupplierHttpRequest.class)))
                .thenReturn(response(ExchangeOutcome.OK, GOOD_DOCUMENT));

        importer.runSnapshot(SUPPLIER);

        verify(snapshotWriter).commit(any(), any(), any(), anyString(), any(), eq(120));
    }

    @Test
    void recordsAFailedExchangeWithoutCommittingASnapshot() {
        when(baseClient.exchange(any(SupplierHttpRequest.class)))
                .thenReturn(response(ExchangeOutcome.PRE_SEND_FAILURE, null));

        importer.runSnapshot(SUPPLIER);

        verify(snapshotWriter).persistFailure(any(), any(), anyString(), any(), anyString());
        verify(snapshotWriter, never()).commit(any(), any(), any(), anyString(), any(), anyInt());
    }

    @Test
    void recordsAnUndecodableDocumentAsAFailedSnapshotRatherThanAnEmptyOne() {
        when(baseClient.exchange(any(SupplierHttpRequest.class)))
                .thenReturn(response(ExchangeOutcome.OK, "<html>maintenance</html>"));

        importer.runSnapshot(SUPPLIER);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(snapshotWriter).persistFailure(any(), any(), anyString(), any(), detail.capture());
        assertThat(detail.getValue()).contains("not a B2.1 document");
        verify(snapshotWriter, never()).commit(any(), any(), any(), anyString(), any(), anyInt());
    }

    @Test
    void refusesToRunWhenNoCodecIsRegisteredForTheBoundNorm() {
        when(adapterRegistry.resolve(SupplierCapability.STOCK_REPORT, ProtocolFamily.EDIWHEEL_B, ProtocolVersion.B2_1))
                .thenReturn(new AdapterResolution.NotConfigured("nothing registered"));

        assertThatThrownBy(() -> importer.runSnapshot(SUPPLIER)).isInstanceOf(SupplierConfigurationException.class);

        verify(baseClient, never()).exchange(any());
    }
}
