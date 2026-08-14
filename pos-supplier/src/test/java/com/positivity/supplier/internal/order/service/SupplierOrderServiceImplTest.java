package com.positivity.supplier.internal.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.supplier.internal.domain.model.SupplierPurchaseOrder;
import com.positivity.supplier.internal.entity.SupplierTransmissionIntentEntity;
import com.positivity.supplier.internal.enums.TransmissionAttemptState;
import com.positivity.supplier.internal.exception.SupplierConflictException;
import com.positivity.supplier.internal.exception.SupplierNotFoundException;
import com.positivity.supplier.internal.exception.SupplierValidationException;
import com.positivity.supplier.internal.repository.SupplierTransmissionIntentRepository;
import com.positivity.supplier.service.model.OrderTransmissionStatus;
import com.positivity.supplier.service.model.TransmissionResolutionRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Transmission ledger reads and manual resolution (ADR-0052 §4, #1226)")
class SupplierOrderServiceImplTest {

    private static final UUID INTENT_ID = UUID.fromString("0198f3a2-4c7e-7a1b-9c2d-3e4f5a6b7c8d");
    private static final UUID PURCHASE_ORDER_ID = UUID.fromString("0198f3a2-4c7e-7a1b-9c2d-000000000001");

    @Mock
    private SupplierTransmissionIntentRepository intentRepository;

    @Mock
    private TransmissionStateWriter stateWriter;

    private SupplierTransmissionIntentEntity intent;
    private SupplierOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SupplierOrderServiceImpl(intentRepository, stateWriter);
        intent = SupplierTransmissionIntentEntity.builder()
                .transmissionIntentId(INTENT_ID)
                .vendorProfileId(UUID.randomUUID())
                .supplierRef("michelin-eu")
                .purchaseOrderId(PURCHASE_ORDER_ID)
                .purchaseOrderNumber("PO-4471")
                .intentType(SupplierPurchaseOrder.IntentType.INITIAL)
                .documentId("DUR0198F3A24C7E7A1B9C2D3E4F5A6B7C8D")
                .attemptState(TransmissionAttemptState.MANUAL_REVIEW)
                .build();
        when(intentRepository.findById(INTENT_ID)).thenReturn(Optional.of(intent));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("ops.jo", "n/a", List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listsTransmissionsForAPurchaseOrder() {
        when(intentRepository.findByPurchaseOrderIdOrderByTransmissionIntentIdDesc(PURCHASE_ORDER_ID))
                .thenReturn(List.of(intent));

        assertThat(service.findTransmissionsByPurchaseOrderId(PURCHASE_ORDER_ID))
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.state()).isEqualTo(OrderTransmissionStatus.State.MANUAL_REVIEW);
                    assertThat(view.documentId()).isEqualTo("DUR0198F3A24C7E7A1B9C2D3E4F5A6B7C8D");
                });
    }

    @Test
    void reportsAnUnknownTransmissionAsNotFound() {
        when(intentRepository.findById(INTENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTransmission(INTENT_ID)).isInstanceOf(SupplierNotFoundException.class);
    }

    @Test
    void recordsTheAuthenticatedCallerAsTheActor() {
        // Read from the security context rather than taken as a parameter: an actor a caller can
        // supply is an actor a caller can choose, and this one settles later disputes about who told
        // the system an order was placed.
        service.resolveTransmission(
                INTENT_ID,
                new TransmissionResolutionRequest(
                        TransmissionResolutionRequest.Action.CONFIRM_WITH_VENDOR_REFERENCE,
                        "called the vendor",
                        "MICH-770412"));

        verify(stateWriter)
                .recordResolution(
                        eq(INTENT_ID),
                        eq(TransmissionAttemptState.CONFIRMED),
                        eq("CONFIRM_WITH_VENDOR_REFERENCE"),
                        eq("ops.jo"),
                        eq("called the vendor"),
                        eq("MICH-770412"));
    }

    @Test
    void refusesToConfirmWithoutTheVendorsOwnReference() {
        // The vendor's reference is the evidence. Without it the operator is asserting a
        // confirmation with nothing to check it against later.
        assertThatThrownBy(() -> service.resolveTransmission(
                        INTENT_ID,
                        new TransmissionResolutionRequest(
                                TransmissionResolutionRequest.Action.CONFIRM_WITH_VENDOR_REFERENCE,
                                "called the vendor",
                                "  ")))
                .isInstanceOf(SupplierValidationException.class);

        verify(stateWriter, never()).recordResolution(any(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    void terminatesTheIntentWhenAnOperatorMarksItNotReceived() {
        service.resolveTransmission(
                INTENT_ID,
                new TransmissionResolutionRequest(
                        TransmissionResolutionRequest.Action.MARK_NOT_RECEIVED, "vendor has no such order", null));

        verify(stateWriter)
                .recordResolution(
                        eq(INTENT_ID),
                        eq(TransmissionAttemptState.FAILED),
                        eq("MARK_NOT_RECEIVED"),
                        anyString(),
                        anyString(),
                        any());
    }

    @Test
    void refusesToResolveATransmissionThatIsNotAwaitingReview() {
        // Every other state is either a definite outcome or work still in progress. Letting an
        // operator overwrite those would let a human contradict a vendor's own answer -- or
        // "confirm" an order the system is about to send.
        for (TransmissionAttemptState state : TransmissionAttemptState.values()) {
            if (state == TransmissionAttemptState.MANUAL_REVIEW) {
                continue;
            }
            intent.setAttemptState(state);

            assertThatThrownBy(() -> service.resolveTransmission(
                            INTENT_ID,
                            new TransmissionResolutionRequest(
                                    TransmissionResolutionRequest.Action.CANCEL, "abandoning", null)))
                    .as("resolution must be refused from %s", state)
                    .isInstanceOf(SupplierConflictException.class);
        }
    }

    @Test
    void offersNoWayToReSendATransmission() {
        // Asserted as an absence, because the absence is the safety property: there is no action on
        // the contract that puts a transmission back on the wire, not even for one an operator is
        // certain never arrived.
        assertThat(TransmissionResolutionRequest.Action.values())
                .extracting(Enum::name)
                .noneMatch(name -> name.contains("RESEND") || name.contains("RETRY"));
    }
}
