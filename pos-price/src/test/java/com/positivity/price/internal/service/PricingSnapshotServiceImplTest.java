package com.positivity.price.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.price.internal.dto.PricingSnapshotCreateRequest;
import com.positivity.price.internal.dto.PricingSnapshotResponse;
import com.positivity.price.internal.entity.PricingSnapshot;
import com.positivity.price.internal.exception.SnapshotNotFoundException;
import com.positivity.price.internal.repository.PricingSnapshotRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Immutable pricing snapshots (#50): what was written is exactly what is read back. */
@ExtendWith(MockitoExtension.class)
@DisplayName("PricingSnapshotServiceImpl")
class PricingSnapshotServiceImplTest {

    private static final UUID SNAPSHOT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f0a01");
    private static final Instant CREATED_AT = Instant.parse("2026-03-01T12:00:00Z");

    @Mock
    private PricingSnapshotRepository pricingSnapshotRepository;

    @InjectMocks
    private PricingSnapshotServiceImpl service;

    private static PricingSnapshotCreateRequest createRequest() {
        PricingSnapshotCreateRequest request = new PricingSnapshotCreateRequest();
        request.setSourceContext("ORDER");
        request.setItemIdentifier("SKU-1");
        request.setQuantity(4);
        request.setPrices("{\"unit\":\"19.99\"}");
        request.setAppliedRules("[\"CUSTOMER_TIER\"]");
        request.setPolicyVersion("2026.03");
        return request;
    }

    @Test
    void storesEveryFieldOfTheSnapshotAndReturnsItsId() {
        when(pricingSnapshotRepository.save(any())).thenAnswer(invocation -> {
            PricingSnapshot snapshot = invocation.getArgument(0);
            snapshot.setSnapshotId(SNAPSHOT_ID);
            return snapshot;
        });

        UUID snapshotId = service.createSnapshot(createRequest());

        assertThat(snapshotId).isEqualTo(SNAPSHOT_ID);
        ArgumentCaptor<PricingSnapshot> saved = ArgumentCaptor.forClass(PricingSnapshot.class);
        verify(pricingSnapshotRepository).save(saved.capture());
        assertThat(saved.getValue().getSourceContext()).isEqualTo("ORDER");
        assertThat(saved.getValue().getItemIdentifier()).isEqualTo("SKU-1");
        assertThat(saved.getValue().getQuantity()).isEqualTo(4);
        assertThat(saved.getValue().getPrices()).isEqualTo("{\"unit\":\"19.99\"}");
        assertThat(saved.getValue().getAppliedRules()).isEqualTo("[\"CUSTOMER_TIER\"]");
        assertThat(saved.getValue().getPolicyVersion()).isEqualTo("2026.03");
    }

    @Test
    void readsBackTheStoredSnapshotUnchanged() {
        PricingSnapshot snapshot = new PricingSnapshot();
        snapshot.setSnapshotId(SNAPSHOT_ID);
        snapshot.setCreatedAt(CREATED_AT);
        snapshot.setSourceContext("ORDER");
        snapshot.setItemIdentifier("SKU-1");
        snapshot.setQuantity(4);
        snapshot.setPrices("{\"unit\":\"19.99\"}");
        snapshot.setAppliedRules("[\"CUSTOMER_TIER\"]");
        snapshot.setPolicyVersion("2026.03");
        when(pricingSnapshotRepository.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));

        PricingSnapshotResponse response = service.getSnapshot(SNAPSHOT_ID);

        assertThat(response.getSnapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(response.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(response.getSourceContext()).isEqualTo("ORDER");
        assertThat(response.getItemIdentifier()).isEqualTo("SKU-1");
        assertThat(response.getQuantity()).isEqualTo(4);
        assertThat(response.getPrices()).isEqualTo("{\"unit\":\"19.99\"}");
        assertThat(response.getAppliedRules()).isEqualTo("[\"CUSTOMER_TIER\"]");
        assertThat(response.getPolicyVersion()).isEqualTo("2026.03");
    }

    @Test
    void failsForASnapshotThatWasNeverWritten() {
        when(pricingSnapshotRepository.findById(SNAPSHOT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSnapshot(SNAPSHOT_ID)).isInstanceOf(SnapshotNotFoundException.class);
    }
}
