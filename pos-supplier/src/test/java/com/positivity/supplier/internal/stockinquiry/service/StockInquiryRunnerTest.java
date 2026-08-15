package com.positivity.supplier.internal.stockinquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.supplier.internal.adapter.ediwheela2.EdiwheelA25StockInquiryCodec;
import com.positivity.supplier.internal.client.SupplierBaseClient;
import com.positivity.supplier.internal.client.SupplierHttpRequest;
import com.positivity.supplier.internal.client.SupplierHttpResponse;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierStockInquiry;
import com.positivity.supplier.internal.domain.model.SupplierStockInquiryResult;
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
import java.time.Duration;
import java.util.List;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Live stock inquiry degradation (#1225, ADR-0049 §4)")
class StockInquiryRunnerTest {

    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");
    private static final UUID LOCATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5f");
    private static final SupplierRef SUPPLIER = new SupplierRef("michelin-eu");

    private static final String GOOD_QUOTE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ew:quote_A2 xmlns:ew="http://www.reifen.net">
              <ew:DocumentID>A2</ew:DocumentID>
              <ew:ErrorHead><ew:ErrorCode>0</ew:ErrorCode></ew:ErrorHead>
              <ew:OrderLine>
                <ew:LineID>000001</ew:LineID>
                <ew:OrderedArticle>
                  <ew:ArticleIdentification><ew:EANUCCArticleID>4019238001234</ew:EANUCCArticleID></ew:ArticleIdentification>
                  <ew:Availability>1</ew:Availability>
                  <ew:ScheduleDetails>
                    <ew:DeliveryDate>2026-08-20</ew:DeliveryDate>
                    <ew:ConfirmedQuantity><ew:QuantityValue>8</ew:QuantityValue></ew:ConfirmedQuantity>
                  </ew:ScheduleDetails>
                </ew:OrderedArticle>
              </ew:OrderLine>
            </ew:quote_A2>
            """;

    @Mock
    private SupplierProfileResolver profileResolver;

    @Mock
    private AdapterRegistry adapterRegistry;

    @Mock
    private SupplierBaseClient baseClient;

    private StockInquiryRunner runner;

    @BeforeEach
    void setUp() {
        runner = new StockInquiryRunner(profileResolver, adapterRegistry, baseClient);
        when(profileResolver.resolveBinding(SUPPLIER, SupplierCapability.STOCK_INQUIRY))
                .thenReturn(binding());
        when(profileResolver.resolvePartyContext(SUPPLIER, LOCATION_ID))
                .thenReturn(new ResolvedPartyAccounts(account("30012456", "91"), account("50098765", "92")));
        when(adapterRegistry.resolve(
                        SupplierCapability.STOCK_INQUIRY, ProtocolFamily.EDIWHEEL_A25, ProtocolVersion.A2_5))
                .thenReturn(new AdapterResolution.Resolved(new EdiwheelA25StockInquiryCodec()));
    }

    private static ResolvedBinding binding() {
        SupplierProfileEntity profile = new SupplierProfileEntity();
        profile.setVendorProfileId(PROFILE_ID);
        profile.setSupplierRef(SUPPLIER.value());
        profile.setEnabled(true);

        SupplierEndpointBindingEntity endpointBinding = new SupplierEndpointBindingEntity();
        endpointBinding.setVendorProfileId(PROFILE_ID);
        endpointBinding.setCapability(SupplierCapability.STOCK_INQUIRY);
        endpointBinding.setProtocolFamily(ProtocolFamily.EDIWHEEL_A25);
        endpointBinding.setProtocolVersion("A2_5");
        endpointBinding.setEnabled(true);

        return new ResolvedBinding(
                profile,
                endpointBinding,
                new SupplierAuthConfigEntity(),
                SupplierCapability.STOCK_INQUIRY,
                ProtocolFamily.EDIWHEEL_A25,
                ProtocolVersion.A2_5);
    }

    private static SupplierAccountEntity account(String number, String agency) {
        SupplierAccountEntity account = new SupplierAccountEntity();
        account.setAccountNumber(number);
        account.setAgencyCode(agency);
        return account;
    }

    private static SupplierStockInquiry inquiry() {
        return new SupplierStockInquiry(
                UUID.randomUUID(), List.of(new SupplierStockInquiry.Line("4019238001234", null, 4)));
    }

    private static SupplierHttpResponse response(ExchangeOutcome outcome, String body) {
        return new SupplierHttpResponse(
                outcome,
                outcome.isSuccess() ? 200 : 503,
                body,
                "corr-1",
                1,
                Duration.ofMillis(120),
                outcome.isSuccess() ? null : "vendor unavailable");
    }

    @Test
    void returnsTheVendorsAnswerWhenTheVendorAnswers() {
        when(baseClient.exchange(any())).thenReturn(response(ExchangeOutcome.OK, GOOD_QUOTE));

        SupplierStockInquiryResult result = runner.inquire(SUPPLIER, LOCATION_ID, inquiry());

        assertThat(result.status()).isEqualTo(SupplierStockInquiryResult.Status.OK);
        assertThat(result.lines()).hasSize(1);
        assertThat(result.lines().getFirst().availableQuantity()).isEqualTo(8);
    }

    @Test
    void sendsTheProfilesBillingAndDeliveryAccountsRatherThanAnythingTheCallerSupplied() {
        when(baseClient.exchange(any())).thenReturn(response(ExchangeOutcome.OK, GOOD_QUOTE));

        runner.inquire(SUPPLIER, LOCATION_ID, inquiry());

        ArgumentCaptor<SupplierHttpRequest> captor = ArgumentCaptor.forClass(SupplierHttpRequest.class);
        verify(baseClient).exchange(captor.capture());
        // Both identities come from the profile. A caller able to supply them could supply a pair
        // the profile does not agree with, and the vendor would answer for the wrong address.
        assertThat(captor.getValue().body()).contains("30012456").contains("50098765");
    }

    @Test
    void reportsAnUnreachableVendorAsUnavailableRatherThanThrowing() {
        when(baseClient.exchange(any())).thenReturn(response(ExchangeOutcome.PRE_SEND_FAILURE, null));

        SupplierStockInquiryResult result = runner.inquire(SUPPLIER, LOCATION_ID, inquiry());

        // A product page has something to render without live stock and nothing to render if this
        // throws.
        assertThat(result.status()).isEqualTo(SupplierStockInquiryResult.Status.SUPPLIER_UNAVAILABLE);
        assertThat(result.lines()).isEmpty();
    }

    @Test
    void reportsAnOpenBreakerAsUnavailableWithoutWaitingOnTheVendor() {
        // The base client answers PRE_SEND_FAILURE immediately when the breaker is open, so the
        // caller-facing latency of a failing vendor is the breaker's, not the vendor's timeout.
        when(baseClient.exchange(any()))
                .thenReturn(new SupplierHttpResponse(
                        ExchangeOutcome.PRE_SEND_FAILURE,
                        null,
                        null,
                        "corr-1",
                        1,
                        Duration.ofMillis(1),
                        "circuit breaker open"));

        SupplierStockInquiryResult result = runner.inquire(SUPPLIER, LOCATION_ID, inquiry());

        assertThat(result.status()).isEqualTo(SupplierStockInquiryResult.Status.SUPPLIER_UNAVAILABLE);
    }

    @Test
    void reportsAReadTimeoutAsUnavailableRatherThanAsAnEmptyWarehouse() {
        when(baseClient.exchange(any())).thenReturn(response(ExchangeOutcome.POST_SEND_AMBIGUOUS, null));

        SupplierStockInquiryResult result = runner.inquire(SUPPLIER, LOCATION_ID, inquiry());

        assertThat(result.status()).isEqualTo(SupplierStockInquiryResult.Status.SUPPLIER_UNAVAILABLE);
        assertThat(result.lines()).isEmpty();
    }

    @Test
    void reportsAnUnreadableAnswerAsUnavailableRatherThanAsNoStock() {
        when(baseClient.exchange(any())).thenReturn(response(ExchangeOutcome.OK, "<html>502</html>"));

        SupplierStockInquiryResult result = runner.inquire(SUPPLIER, LOCATION_ID, inquiry());

        // Our parsing problem must not become the vendor's empty warehouse.
        assertThat(result.status()).isEqualTo(SupplierStockInquiryResult.Status.SUPPLIER_UNAVAILABLE);
    }

    @Test
    void reportsAnUnboundCapabilityWithoutCallingAnyone() {
        when(profileResolver.resolveBinding(SUPPLIER, SupplierCapability.STOCK_INQUIRY))
                .thenThrow(new SupplierConfigurationException(
                        SupplierConfigurationException.CAPABILITY_NOT_CONFIGURED, "not bound"));

        SupplierStockInquiryResult result = runner.inquire(SUPPLIER, LOCATION_ID, inquiry());

        assertThat(result.status()).isEqualTo(SupplierStockInquiryResult.Status.CAPABILITY_NOT_CONFIGURED);
        verifyNoInteractions(baseClient);
    }

    @Test
    void reportsAnUnmappedDeliveryLocationAsConfigurationErrorBeforeAnyNetworkCall() {
        when(profileResolver.resolvePartyContext(SUPPLIER, LOCATION_ID))
                .thenThrow(new SupplierConfigurationException(
                        SupplierConfigurationException.MISSING_DELIVERY_MAPPING, "no mapping"));

        SupplierStockInquiryResult result = runner.inquire(SUPPLIER, LOCATION_ID, inquiry());

        // Asking anyway would return an answer about whichever address the vendor defaults to —
        // right-looking, and about the wrong place.
        assertThat(result.status()).isEqualTo(SupplierStockInquiryResult.Status.CONFIGURATION_ERROR);
        verify(baseClient, never()).exchange(any());
    }

    @Test
    void reportsAProfileWithNoDeliveryAccountAsConfigurationErrorRatherThanAskingWithoutOne() {
        when(profileResolver.resolvePartyContext(SUPPLIER, LOCATION_ID))
                .thenReturn(new ResolvedPartyAccounts(account("30012456", "91"), null));

        SupplierStockInquiryResult result = runner.inquire(SUPPLIER, LOCATION_ID, inquiry());

        assertThat(result.status()).isEqualTo(SupplierStockInquiryResult.Status.CONFIGURATION_ERROR);
        verify(baseClient, never()).exchange(any());
    }

    @Test
    void reportsAMissingCodecAsCapabilityNotConfigured() {
        when(adapterRegistry.resolve(
                        SupplierCapability.STOCK_INQUIRY, ProtocolFamily.EDIWHEEL_A25, ProtocolVersion.A2_5))
                .thenReturn(new AdapterResolution.NotConfigured("no codec bound for STOCK_INQUIRY/EDIWHEEL_A25/A2_5"));

        SupplierStockInquiryResult result = runner.inquire(SUPPLIER, LOCATION_ID, inquiry());

        assertThat(result.status()).isEqualTo(SupplierStockInquiryResult.Status.CAPABILITY_NOT_CONFIGURED);
        verifyNoInteractions(baseClient);
    }

    @Test
    void marksTheExchangeRetryableBecauseAnInquiryCommitsNothing() {
        when(baseClient.exchange(any())).thenReturn(response(ExchangeOutcome.OK, GOOD_QUOTE));

        runner.inquire(SUPPLIER, LOCATION_ID, inquiry());

        ArgumentCaptor<SupplierHttpRequest> captor = ArgumentCaptor.forClass(SupplierHttpRequest.class);
        verify(baseClient).exchange(captor.capture());
        assertThat(captor.getValue().idempotent()).isTrue();
    }
}
