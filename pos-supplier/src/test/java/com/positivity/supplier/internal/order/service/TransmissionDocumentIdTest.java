package com.positivity.supplier.internal.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Wire document id derivation (ADR-0052 §1, #1226)")
class TransmissionDocumentIdTest {

    private static final UUID INTENT = UUID.fromString("0198f3a2-4c7e-7a1b-9c2d-3e4f5a6b7c8d");

    @Test
    void isTheSameForEveryRetryOfOneIntent() {
        // The core idempotency property: a retry of one intent presents the vendor the same
        // reference, which is what gives its deduplication something to work with and what keeps a
        // later status query answerable.
        assertThat(TransmissionDocumentId.forIntent(INTENT)).isEqualTo(TransmissionDocumentId.forIntent(INTENT));
    }

    @Test
    void differsForEveryDistinctIntent() {
        // The other half: a revision, a replacement or a split is a new intent, and must never
        // reuse a reference the vendor already associates with an order it holds.
        assertThat(TransmissionDocumentId.forIntent(UUID.randomUUID()))
                .isNotEqualTo(TransmissionDocumentId.forIntent(UUID.randomUUID()));
    }

    @Test
    void fitsTheNormsThirtyFiveCharacterField() {
        // C1.0 caps CustomerReference/DocumentID at 35. Sending 36 risks silent truncation at the
        // vendor, and a truncated reference is one an order-status query can no longer find --
        // which turns every ambiguous outcome on that vendor into a manual review.
        assertThat(TransmissionDocumentId.forIntent(INTENT)).hasSize(35);
    }

    @Test
    void isRecognisablyOursAndCarriesTheWholeIntentId() {
        assertThat(TransmissionDocumentId.forIntent(INTENT)).isEqualTo("DUR0198F3A24C7E7A1B9C2D3E4F5A6B7C8D");
    }

    @Test
    void dependsOnNothingButTheIntentId() {
        // No clock, no counter, no random source. If it did, the "same on retry" guarantee would
        // depend on remembering to persist the value before the first send -- exactly the step a
        // crash skips.
        UUID intent = UUID.randomUUID();
        String first = TransmissionDocumentId.forIntent(intent);

        assertThat(TransmissionDocumentId.forIntent(intent)).isEqualTo(first);
    }
}
