package com.positivity.shopmanager.cap138;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.positivity.shopmanager.internal.entity.HrIntegrationLog;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HrIntegrationLog#requireEventId()} @PrePersist
 * callback (T-03 / F-04).
 *
 * These tests verify the invariant that eventId must be non-null before the
 * entity is persisted, since it serves as the deduplication key for the BR2
 * idempotency guard.
 */
class HrIntegrationLogTest {

    @Test
    void requireEventId_whenEventIdNull_throwsIllegalStateException() {
        HrIntegrationLog log = HrIntegrationLog.builder()
                .personId("HR-001")
                .eventType("MECHANIC_UPSERTED")
                .status("PROCESSED")
                .build();

        assertThatThrownBy(log::requireEventId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    void requireEventId_whenEventIdPresent_doesNotThrow() {
        HrIntegrationLog log = HrIntegrationLog.builder()
                .eventId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .personId("HR-001")
                .eventType("MECHANIC_UPSERTED")
                .status("PROCESSED")
                .build();

        assertThatCode(log::requireEventId).doesNotThrowAnyException();
    }
}
