package com.positivity.supplier.internal.pricecatalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.supplier.internal.adapter.ediwheelb.EdiwheelB40PricatCodec;
import com.positivity.supplier.internal.client.SupplierBaseClient;
import com.positivity.supplier.internal.client.SupplierHttpRequest;
import com.positivity.supplier.internal.client.SupplierHttpResponse;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.entity.PriceCatalogImportEntity;
import com.positivity.supplier.internal.entity.SupplierAccountEntity;
import com.positivity.supplier.internal.entity.SupplierAuthConfigEntity;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.enums.PriceCatalogErrorCode;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.pricecatalog.service.ProductCodeResolver.Resolution;
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
@DisplayName("PRICAT import orchestration (#1224)")
class PriceCatalogImporterTest {

    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");
    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d");
    private static final SupplierRef SUPPLIER = new SupplierRef("michelin-eu");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-13T09:00:00Z"), ZoneOffset.UTC);

    private static final String GOOD_DOCUMENT = """
            {"envelopeHeader":{"documentId":"DOC-1","date":"202608130900","countryCode":"SE","currency":"SEK",
              "ediwheelVersion":"B4.0","errorCode":"0"},
             "articles":[{"pos":1,"ean":"3528709999083","supplierCode":"999908","netValue":"90.00",
              "netValueValidFrom":"20260201"}]}
            """;

    @Mock
    private SupplierProfileResolver profileResolver;

    @Mock
    private AdapterRegistry adapterRegistry;

    @Mock
    private SupplierBaseClient baseClient;

    @Mock
    private ProductCodeResolver productCodeResolver;

    @Mock
    private PriceCatalogStagingWriter stagingWriter;

    private PriceCatalogImporter service;

    @BeforeEach
    void setUp() {
        service = new PriceCatalogImporter(
                profileResolver, adapterRegistry, baseClient, productCodeResolver, stagingWriter, CLOCK);
        ReflectionTestUtils.setField(service, "defaultChunkSize", 500);

        when(profileResolver.resolveBinding(SUPPLIER, SupplierCapability.PRICE_CATALOG))
                .thenReturn(binding(null));
        when(profileResolver.resolvePartyContext(SUPPLIER, null))
                .thenReturn(new ResolvedPartyAccounts(billing(), null));
        when(adapterRegistry.resolve(SupplierCapability.PRICE_CATALOG, ProtocolFamily.EDIWHEEL_B, ProtocolVersion.B4_0))
                .thenReturn(new AdapterResolution.Resolved(new EdiwheelB40PricatCodec(new ObjectMapper())));
        when(productCodeResolver.resolve("EAN", "3528709999083")).thenReturn(new Resolution.Matched(PRODUCT_ID));
        when(stagingWriter.commit(any(), any(), any(), any(), any(), anyString(), any(), anyInt()))
                .thenReturn(PriceCatalogImportEntity.builder().build());
        when(stagingWriter.persistFailure(any(), any(), any(), any(), anyString(), any(), any()))
                .thenReturn(PriceCatalogImportEntity.builder().build());
    }

    private static ResolvedBinding binding(Integer chunkSizeOverride) {
        SupplierProfileEntity profile = new SupplierProfileEntity();
        profile.setVendorProfileId(PROFILE_ID);
        profile.setSupplierRef(SUPPLIER.value());
        profile.setEnabled(true);

        SupplierEndpointBindingEntity endpointBinding = new SupplierEndpointBindingEntity();
        endpointBinding.setId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5e"));
        endpointBinding.setVendorProfileId(PROFILE_ID);
        endpointBinding.setCapability(SupplierCapability.PRICE_CATALOG);
        endpointBinding.setProtocolFamily(ProtocolFamily.EDIWHEEL_B);
        endpointBinding.setProtocolVersion("B4_0");
        endpointBinding.setEnabled(true);
        endpointBinding.setEventChunkSize(chunkSizeOverride);

        return new ResolvedBinding(
                profile,
                endpointBinding,
                new SupplierAuthConfigEntity(),
                SupplierCapability.PRICE_CATALOG,
                ProtocolFamily.EDIWHEEL_B,
                ProtocolVersion.B4_0);
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
    void stagesTheDocumentOnASuccessfulFetch() {
        when(baseClient.exchange(any(SupplierHttpRequest.class)))
                .thenReturn(response(ExchangeOutcome.OK, GOOD_DOCUMENT));

        service.runImport(SUPPLIER);

        verify(stagingWriter).commit(any(), any(), any(), any(), any(), anyString(), any(), eq(500));
        verify(stagingWriter, never()).persistFailure(any(), any(), any(), any(), anyString(), any(), any());
    }

    @Test
    void sendsTheBuyerAccountAsTheVendorQuery() {
        when(baseClient.exchange(any(SupplierHttpRequest.class)))
                .thenReturn(response(ExchangeOutcome.OK, GOOD_DOCUMENT));

        service.runImport(SUPPLIER);

        ArgumentCaptor<SupplierHttpRequest> captor = ArgumentCaptor.forClass(SupplierHttpRequest.class);
        verify(baseClient).exchange(captor.capture());
        assertThat(captor.getValue().queryParams())
                .containsEntry("buyerParty", "30012456")
                .containsEntry("agencyCode", "91");
        assertThat(captor.getValue().idempotent()).isTrue();
    }

    @Test
    void prefersTheBindingsChunkSizeOverTheDeploymentDefault() {
        when(profileResolver.resolveBinding(SUPPLIER, SupplierCapability.PRICE_CATALOG))
                .thenReturn(binding(120));
        when(baseClient.exchange(any(SupplierHttpRequest.class)))
                .thenReturn(response(ExchangeOutcome.OK, GOOD_DOCUMENT));

        service.runImport(SUPPLIER);

        verify(stagingWriter).commit(any(), any(), any(), any(), any(), anyString(), any(), eq(120));
    }

    @Test
    void recordsAFailedExchangeWithoutStagingAnything() {
        when(baseClient.exchange(any(SupplierHttpRequest.class)))
                .thenReturn(response(ExchangeOutcome.PRE_SEND_FAILURE, null));

        service.runImport(SUPPLIER);

        verify(stagingWriter)
                .persistFailure(
                        any(), any(), any(), any(), anyString(), anyString(), eq(PriceCatalogErrorCode.FETCH_FAILED));
        verify(stagingWriter, never()).commit(any(), any(), any(), any(), any(), anyString(), any(), anyInt());
    }

    @Test
    void recordsAnUndecodableDocumentAsAFailedImport() {
        when(baseClient.exchange(any(SupplierHttpRequest.class)))
                .thenReturn(response(ExchangeOutcome.OK, "<html>maintenance</html>"));

        service.runImport(SUPPLIER);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(stagingWriter)
                .persistFailure(
                        any(),
                        any(),
                        any(),
                        any(),
                        anyString(),
                        detail.capture(),
                        eq(PriceCatalogErrorCode.DECODE_FAILED));
        assertThat(detail.getValue()).contains("not a B4.0 catalog document");
        verify(stagingWriter, never()).commit(any(), any(), any(), any(), any(), anyString(), any(), anyInt());
    }

    @Test
    void refusesToRunWhenNoCodecIsRegisteredForTheBoundNorm() {
        when(adapterRegistry.resolve(SupplierCapability.PRICE_CATALOG, ProtocolFamily.EDIWHEEL_B, ProtocolVersion.B4_0))
                .thenReturn(new AdapterResolution.NotConfigured("nothing registered"));

        assertThatThrownBy(() -> service.runImport(SUPPLIER)).isInstanceOf(SupplierConfigurationException.class);

        verify(baseClient, never()).exchange(any());
    }
}
