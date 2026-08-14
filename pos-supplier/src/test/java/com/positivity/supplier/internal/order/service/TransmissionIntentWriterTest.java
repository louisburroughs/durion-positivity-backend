package com.positivity.supplier.internal.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.supplier.SupplierOrderRequestedLine;
import com.positivity.domainevents.supplier.SupplierOrderRequestedV1;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.entity.SupplierTransmissionIntentEntity;
import com.positivity.supplier.internal.entity.SupplierTransmissionLineEntity;
import com.positivity.supplier.internal.enums.TransmissionAttemptState;
import com.positivity.supplier.internal.repository.SupplierAccountRepository;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import com.positivity.supplier.internal.repository.SupplierTransmissionIntentRepository;
import com.positivity.supplier.internal.repository.SupplierTransmissionLineRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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
@DisplayName("Transmission intent minting (ADR-0052 §1, #1226)")
class TransmissionIntentWriterTest {

    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");
    private static final UUID PURCHASE_ORDER_ID = UUID.fromString("0198f3a2-4c7e-7a1b-9c2d-000000000001");

    @Mock
    private SupplierProfileRepository profileRepository;

    @Mock
    private SupplierAccountRepository accountRepository;

    @Mock
    private SupplierTransmissionIntentRepository intentRepository;

    @Mock
    private SupplierTransmissionLineRepository lineRepository;

    private TransmissionIntentWriter writer;

    @BeforeEach
    void setUp() {
        writer = new TransmissionIntentWriter(profileRepository, accountRepository, intentRepository, lineRepository);

        SupplierProfileEntity profile = new SupplierProfileEntity();
        profile.setVendorProfileId(PROFILE_ID);
        profile.setSupplierRef("michelin-eu");
        profile.setEnabled(true);
        when(profileRepository.findBySupplierRef("michelin-eu")).thenReturn(Optional.of(profile));
        when(accountRepository.findByVendorProfileIdAndRole(any(), any())).thenReturn(List.of());
        when(intentRepository.findByActiveIntentKey(anyString())).thenReturn(Optional.empty());
        when(intentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static SupplierOrderRequestedV1 command(SupplierOrderRequestedV1.IntentType type, int revision) {
        return new SupplierOrderRequestedV1(
                PURCHASE_ORDER_ID,
                revision,
                type,
                "michelin-eu",
                UUID.fromString("0198f3a2-4c7e-7a1b-9c2d-000000000002"),
                "PO-4471",
                List.of(new SupplierOrderRequestedLine(1, "3528709999083", "999908", 4, LocalDate.of(2026, 8, 10))));
    }

    @Test
    void recordsTheOrderAsDispatchableWorkWithoutSendingAnything() {
        Optional<SupplierTransmissionIntentEntity> minted =
                writer.mint(command(SupplierOrderRequestedV1.IntentType.INITIAL, 0), "corr-1");

        assertThat(minted).isPresent();
        assertThat(minted.get().getAttemptState()).isEqualTo(TransmissionAttemptState.PENDING);
        assertThat(minted.get().getDocumentId())
                .isEqualTo(TransmissionDocumentId.forIntent(minted.get().getTransmissionIntentId()));
    }

    @Test
    void writesTheLinesSoADeferredDispatchCanRebuildTheSameDocument() {
        writer.mint(command(SupplierOrderRequestedV1.IntentType.INITIAL, 0), "corr-1");

        ArgumentCaptor<SupplierTransmissionLineEntity> line =
                ArgumentCaptor.forClass(SupplierTransmissionLineEntity.class);
        verify(lineRepository).save(line.capture());
        assertThat(line.getValue().getLineNumber()).isEqualTo(1);
        assertThat(line.getValue().getQuantity()).isEqualTo(4);
        assertThat(line.getValue().getRequestedDeliveryDate()).isEqualTo(LocalDate.of(2026, 8, 10));
    }

    @Test
    void ignoresAReIssuedCommandForAnOrderThatAlreadyHasAnActiveIntent() {
        // Idempotency by eventId handles a redelivered message. It does NOT handle the same order
        // requested again with a fresh event id -- a retrying producer, a replayed topic, an
        // impatient operator -- and minting a second intent there would give the order a second
        // document id and a second delivery.
        when(intentRepository.findByActiveIntentKey(anyString()))
                .thenReturn(Optional.of(SupplierTransmissionIntentEntity.builder()
                        .transmissionIntentId(UUID.randomUUID())
                        .attemptState(TransmissionAttemptState.CONFIRMED)
                        .build()));

        assertThat(writer.mint(command(SupplierOrderRequestedV1.IntentType.INITIAL, 0), "corr-2"))
                .isEmpty();

        verify(intentRepository, never()).save(any());
        verify(lineRepository, never()).save(any());
    }

    @Test
    void claimsATupleSoTheDuplicateCheckHasSomethingToFind() {
        SupplierTransmissionIntentEntity minted = writer.mint(
                        command(SupplierOrderRequestedV1.IntentType.INITIAL, 0), "corr-1")
                .orElseThrow();

        assertThat(minted.getActiveIntentKey())
                .isEqualTo(SupplierTransmissionIntentEntity.activeKeyFor(
                        PROFILE_ID,
                        com.positivity.supplier.internal.domain.model.SupplierPurchaseOrder.IntentType.INITIAL,
                        PURCHASE_ORDER_ID,
                        0));
    }

    @Test
    void treatsARevisionAsADifferentOrderToPlace() {
        // Different revision, different tuple, different intent -- and therefore a different
        // document id, which is what stops a vendor merging a revision into the original.
        String initialKey = writer.mint(command(SupplierOrderRequestedV1.IntentType.INITIAL, 0), "corr-1")
                .orElseThrow()
                .getActiveIntentKey();
        String revisionKey = writer.mint(command(SupplierOrderRequestedV1.IntentType.REVISION, 1), "corr-2")
                .orElseThrow()
                .getActiveIntentKey();

        assertThat(initialKey).isNotEqualTo(revisionKey);
    }

    @Test
    void givesDistinctDocumentIdsToDistinctIntentsForOnePurchaseOrder() {
        String first = writer.mint(command(SupplierOrderRequestedV1.IntentType.INITIAL, 0), "corr-1")
                .orElseThrow()
                .getDocumentId();
        String second = writer.mint(command(SupplierOrderRequestedV1.IntentType.REPLACEMENT, 1), "corr-2")
                .orElseThrow()
                .getDocumentId();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void refusesAVendorAliasThisDeploymentDoesNotHave() {
        when(profileRepository.findBySupplierRef("michelin-eu")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> writer.mint(command(SupplierOrderRequestedV1.IntentType.INITIAL, 0), "corr-1"))
                .isInstanceOf(TransmissionIntentWriter.UnknownSupplierException.class);
    }

    @Test
    void refusesADisabledProfile() {
        SupplierProfileEntity disabled = new SupplierProfileEntity();
        disabled.setVendorProfileId(PROFILE_ID);
        disabled.setSupplierRef("michelin-eu");
        disabled.setEnabled(false);
        when(profileRepository.findBySupplierRef("michelin-eu")).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> writer.mint(command(SupplierOrderRequestedV1.IntentType.INITIAL, 0), "corr-1"))
                .isInstanceOf(TransmissionIntentWriter.UnknownSupplierException.class);
    }
}
