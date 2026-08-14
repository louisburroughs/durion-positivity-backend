package com.positivity.supplier.internal.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.supplier.internal.adapter.ediwheelc1.EdiwheelC10OrderCodec;
import com.positivity.supplier.internal.client.SupplierBaseClient;
import com.positivity.supplier.internal.client.SupplierHttpRequest;
import com.positivity.supplier.internal.client.SupplierHttpResponse;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierOrderResult;
import com.positivity.supplier.internal.domain.model.SupplierPurchaseOrder;
import com.positivity.supplier.internal.entity.SupplierAccountEntity;
import com.positivity.supplier.internal.entity.SupplierAuthConfigEntity;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.entity.SupplierTransmissionIntentEntity;
import com.positivity.supplier.internal.entity.SupplierTransmissionLineEntity;
import com.positivity.supplier.internal.enums.TransmissionAttemptState;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.registry.AdapterRegistry;
import com.positivity.supplier.internal.registry.AdapterResolution;
import com.positivity.supplier.internal.repository.SupplierTransmissionLineRepository;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The duplicate-order safety properties of dispatch (ADR-0052 §§2–3, §5/§6).
 *
 * <p>These are acceptance criteria, not hardening. Each test pins one step of the sequence whose
 * violation costs a customer a second truckload of tyres.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Order transmission dispatch (ADR-0052, #1226)")
class OrderTransmissionDispatcherTest {

    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");
    private static final UUID INTENT_ID = UUID.fromString("0198f3a2-4c7e-7a1b-9c2d-3e4f5a6b7c8d");
    private static final String DOCUMENT_ID = "DUR0198F3A24C7E7A1B9C2D3E4F5A6B7C8D";

    @Mock
    private SupplierProfileResolver profileResolver;

    @Mock
    private AdapterRegistry adapterRegistry;

    @Mock
    private SupplierBaseClient baseClient;

    @Mock
    private SupplierTransmissionLineRepository lineRepository;

    @Mock
    private TransmissionStateWriter stateWriter;

    @Mock
    private OrderTransmissionReconciler reconciler;

    private final EdiwheelC10OrderCodec codec = new EdiwheelC10OrderCodec();

    private OrderTransmissionDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new OrderTransmissionDispatcher(
                profileResolver, adapterRegistry, baseClient, lineRepository, stateWriter, reconciler);

        SupplierProfileEntity profile = new SupplierProfileEntity();
        profile.setVendorProfileId(PROFILE_ID);
        profile.setSupplierRef("michelin-eu");
        profile.setEnabled(true);

        SupplierEndpointBindingEntity binding = new SupplierEndpointBindingEntity();
        binding.setCapability(SupplierCapability.ORDER_CREATE);
        binding.setProtocolFamily(ProtocolFamily.EDIWHEEL_C1);
        binding.setProtocolVersion("C1_0");

        when(profileResolver.resolveBinding(any(), eq(SupplierCapability.ORDER_CREATE)))
                .thenReturn(new ResolvedBinding(
                        profile,
                        binding,
                        new SupplierAuthConfigEntity(),
                        SupplierCapability.ORDER_CREATE,
                        ProtocolFamily.EDIWHEEL_C1,
                        ProtocolVersion.C1_0));

        SupplierAccountEntity billing = new SupplierAccountEntity();
        billing.setAccountNumber("30012456");
        billing.setAgencyCode("91");
        when(profileResolver.resolvePartyContext(any(), any())).thenReturn(new ResolvedPartyAccounts(billing, null));

        when(adapterRegistry.resolve(eq(SupplierCapability.ORDER_CREATE), any(), any()))
                .thenReturn(new AdapterResolution.Resolved(codec));

        SupplierTransmissionLineEntity line = SupplierTransmissionLineEntity.builder()
                .transmissionIntentId(INTENT_ID)
                .lineNumber(1)
                .articleEan("3528709999083")
                .quantity(4)
                .build();
        when(lineRepository.findByTransmissionIntentIdOrderByLineNumberAsc(INTENT_ID))
                .thenReturn(List.of(line));

        when(stateWriter.markDispatching(eq(INTENT_ID), anyString())).thenReturn(true);
    }

    private static SupplierTransmissionIntentEntity intent() {
        return SupplierTransmissionIntentEntity.builder()
                .transmissionIntentId(INTENT_ID)
                .vendorProfileId(PROFILE_ID)
                .supplierRef("michelin-eu")
                .purchaseOrderId(UUID.fromString("0198f3a2-4c7e-7a1b-9c2d-000000000001"))
                .intentType(SupplierPurchaseOrder.IntentType.INITIAL)
                .revision(0)
                .documentId(DOCUMENT_ID)
                .attemptState(TransmissionAttemptState.PENDING)
                .build();
    }

    private static SupplierHttpResponse response(ExchangeOutcome outcome, Integer status, String body) {
        return new SupplierHttpResponse(outcome, status, body, "corr-1", 1, Duration.ofMillis(10), null);
    }

    private static String confirmation() {
        return """
                <ew:order_response xmlns:ew="http://www.reifen.net">
                    <ew:DocumentID>C1</ew:DocumentID>
                    <ew:ErrorHead><ew:ErrorCode>0</ew:ErrorCode></ew:ErrorHead>
                    <ew:SupplierOrderNumber><ew:DocumentID>MICH-770412</ew:DocumentID></ew:SupplierOrderNumber>
                </ew:order_response>
                """;
    }

    @Test
    void commitsDispatchingBeforeTheRequestIsSent() {
        // The single most load-bearing ordering in the module. Once DISPATCHING is committed, no
        // path automatically re-sends, so a crash right after the body goes out cannot be mistaken
        // for an order that was never placed.
        when(baseClient.exchange(any())).thenReturn(response(ExchangeOutcome.OK, 200, confirmation()));

        dispatcher.dispatch(intent());

        InOrder ordered = inOrder(stateWriter, baseClient);
        ordered.verify(stateWriter).markDispatching(eq(INTENT_ID), anyString());
        ordered.verify(baseClient).exchange(any());
    }

    @Test
    void neverMarksAnIntentDispatchingWhenTheDocumentCannotBeBuilt() {
        // An encode failure must leave the attempt in PENDING, where a re-dispatch after the
        // configuration fix is safe. Entering DISPATCHING here would cost an operator a manual
        // reconciliation for an order that never left the building.
        when(profileResolver.resolveBinding(any(), eq(SupplierCapability.ORDER_CREATE)))
                .thenThrow(new SupplierConfigurationException(
                        SupplierConfigurationException.CAPABILITY_NOT_CONFIGURED, "ORDER_CREATE is not bound"));

        dispatcher.dispatch(intent());

        verify(stateWriter, never()).markDispatching(any(), anyString());
        verify(baseClient, never()).exchange(any());
        verify(stateWriter).recordPreSendFailure(eq(INTENT_ID), anyString());
    }

    @Test
    void sendsExactlyOnceAndNeverRetriesTheCreateItself() {
        when(baseClient.exchange(any())).thenReturn(response(ExchangeOutcome.OK, 200, confirmation()));

        dispatcher.dispatch(intent());

        verify(baseClient, times(1)).exchange(any());
    }

    @Test
    void marksTheRequestNonIdempotentWhateverTheCodecSaid() {
        // Belt and braces on the one call where a transport-level retry means a duplicate physical
        // order.
        when(baseClient.exchange(any())).thenReturn(response(ExchangeOutcome.OK, 200, confirmation()));

        dispatcher.dispatch(intent());

        ArgumentCaptor<SupplierHttpRequest> request = ArgumentCaptor.forClass(SupplierHttpRequest.class);
        verify(baseClient).exchange(request.capture());
        assertThat(request.getValue().idempotent()).isFalse();
        assertThat(request.getValue().body()).contains(DOCUMENT_ID);
    }

    @Test
    void appliesTheVendorsDecisionWhenItAnswers() {
        when(baseClient.exchange(any())).thenReturn(response(ExchangeOutcome.OK, 200, confirmation()));

        dispatcher.dispatch(intent());

        ArgumentCaptor<SupplierOrderResult> result = ArgumentCaptor.forClass(SupplierOrderResult.class);
        verify(stateWriter).recordCreateResult(eq(INTENT_ID), result.capture());
        assertThat(result.getValue().status()).isEqualTo(SupplierOrderResult.Status.CONFIRMED);
        assertThat(result.getValue().supplierOrderNumber()).isEqualTo("MICH-770412");
        verify(reconciler, never()).reconcile(any(), anyString());
    }

    @Test
    void reconcilesRatherThanRetryingAfterAnAmbiguousOutcome() {
        // A read timeout after the body went out. The vendor may be holding a real order, so the
        // only permitted moves are asking it and asking a human -- never sending again.
        when(baseClient.exchange(any()))
                .thenReturn(new SupplierHttpResponse(
                        ExchangeOutcome.POST_SEND_AMBIGUOUS,
                        null,
                        null,
                        "corr-1",
                        1,
                        Duration.ofSeconds(30),
                        "read timed out"));

        dispatcher.dispatch(intent());

        verify(reconciler).reconcile(eq(INTENT_ID), anyString());
        verify(stateWriter, never()).recordCreateResult(any(), any());
        verify(stateWriter, never()).recordPreSendFailure(any(), anyString());
        verify(baseClient, times(1)).exchange(any());
    }

    @Test
    void reconcilesWhenTheVendorAnswersSomethingUnreadable() {
        // The vendor answered; we could not read it. That is ambiguity, not failure -- the order may
        // well be accepted, and calling it "not ordered" is how a duplicate happens.
        when(baseClient.exchange(any())).thenReturn(response(ExchangeOutcome.OK, 200, "<nonsense/>"));

        dispatcher.dispatch(intent());

        verify(reconciler).reconcile(eq(INTENT_ID), anyString());
        verify(stateWriter, never()).recordCreateResult(any(), any());
    }

    @Test
    void returnsToPendingAfterAFailureThatPrecededTransmission() {
        // Connection refused: the vendor cannot know about it, so a later re-dispatch from the queue
        // is safe -- and presents the same document id.
        when(baseClient.exchange(any()))
                .thenReturn(new SupplierHttpResponse(
                        ExchangeOutcome.PRE_SEND_FAILURE,
                        null,
                        null,
                        "corr-1",
                        1,
                        Duration.ofMillis(20),
                        "connection refused"));

        dispatcher.dispatch(intent());

        verify(stateWriter).recordPreSendFailure(eq(INTENT_ID), anyString());
        verify(reconciler, never()).reconcile(any(), anyString());
    }

    @Test
    void treatsADefiniteFourHundredAsARejectionRatherThanAnAmbiguity() {
        when(baseClient.exchange(any())).thenReturn(response(ExchangeOutcome.DEFINITIVE_REJECTION, 400, """
                        <ew:order_response xmlns:ew="http://www.reifen.net">
                            <ew:ErrorHead>
                                <ew:ErrorCode>913</ew:ErrorCode>
                                <ew:ErrorText>Buyer party unknown</ew:ErrorText>
                            </ew:ErrorHead>
                        </ew:order_response>
                        """));

        dispatcher.dispatch(intent());

        ArgumentCaptor<SupplierOrderResult> result = ArgumentCaptor.forClass(SupplierOrderResult.class);
        verify(stateWriter).recordCreateResult(eq(INTENT_ID), result.capture());
        assertThat(result.getValue().status()).isEqualTo(SupplierOrderResult.Status.REJECTED);
        assertThat(result.getValue().vendorErrorCode()).isEqualTo("913");
    }

    @Test
    void doesNotSendWhenAnotherInstanceAlreadyClaimedTheIntent() {
        when(stateWriter.markDispatching(eq(INTENT_ID), anyString())).thenReturn(false);

        dispatcher.dispatch(intent());

        verify(baseClient, never()).exchange(any());
    }
}
