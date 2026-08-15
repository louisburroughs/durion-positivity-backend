package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.entity.IdempotencyKey;
import com.positivity.workorder.internal.repository.IdempotencyKeyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Idempotency keys are scoped by operation, so the same client key used for two different
 * operations never collides, and a key past its 24-hour window no longer replays.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("IdempotencyServiceImpl")
class IdempotencyServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final Instant NOT_EXPIRED = NOW.plusSeconds(3600);
    private static final Instant EXPIRED = NOW.minusSeconds(3600);
    private static final Instant EXPECTED_EXPIRY = NOW.plusSeconds(24 * 3600);
    private static final String KEY = "client-key-1";
    private static final UUID WORKORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f6a01");
    private static final UUID CHANGE_REQUEST_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f6a02");
    private static final UUID LABOR_ENTRY_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f6a03");
    private static final UUID PART_USAGE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f6a04");
    private static final UUID PART_ADJUSTMENT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f6a05");
    private static final UUID INVOICE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f6a06");

    @Mock
    private IdempotencyKeyRepository repository;

    private IdempotencyServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyServiceImpl(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static IdempotencyKey key(Instant expiresAt) {
        IdempotencyKey key = new IdempotencyKey();
        key.setKeyValue("scoped");
        key.setExpiresAt(expiresAt);
        return key;
    }

    private void stubKey(String scopedKey, IdempotencyKey stored) {
        when(repository.findByKeyValue(scopedKey)).thenReturn(Optional.ofNullable(stored));
    }

    private IdempotencyKey savedKey() {
        ArgumentCaptor<IdempotencyKey> saved = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(repository).save(saved.capture());
        return saved.getValue();
    }

    @Nested
    @DisplayName("workorder keys")
    class WorkorderKeys {

        @Test
        void replaysTheWorkorderBehindALiveKey() {
            IdempotencyKey stored = key(NOT_EXPIRED);
            stored.setWorkorderId(WORKORDER_ID);
            stubKey("workorder.default:" + KEY, stored);

            assertThat(service.getExistingWorkorderId(KEY)).contains(WORKORDER_ID);
        }

        @Test
        void scopesTheLookupToTheNamedOperation() {
            IdempotencyKey stored = key(NOT_EXPIRED);
            stored.setWorkorderId(WORKORDER_ID);
            stubKey("estimate.promote:" + KEY, stored);

            assertThat(service.getExistingWorkorderId("estimate.promote", KEY)).contains(WORKORDER_ID);
            // The same client key under the default scope is a different key entirely.
            assertThat(service.getExistingWorkorderId(KEY)).isEmpty();
        }

        @Test
        void ignoresAnExpiredKey() {
            IdempotencyKey stored = key(EXPIRED);
            stored.setWorkorderId(WORKORDER_ID);
            stubKey("workorder.default:" + KEY, stored);

            assertThat(service.getExistingWorkorderId(KEY)).isEmpty();
        }

        @Test
        void returnsEmptyWhenNoKeyWasEverRegistered() {
            stubKey("workorder.default:" + KEY, null);

            assertThat(service.getExistingWorkorderId(KEY)).isEmpty();
        }

        @Test
        void registersTheKeyWithATwentyFourHourWindow() {
            service.registerKey(KEY, WORKORDER_ID);

            IdempotencyKey saved = savedKey();
            assertThat(saved.getKeyValue()).isEqualTo("workorder.default:" + KEY);
            assertThat(saved.getWorkorderId()).isEqualTo(WORKORDER_ID);
            assertThat(saved.getExpiresAt()).isEqualTo(EXPECTED_EXPIRY);
        }

        @Test
        void registersAnOperationScopedKey() {
            service.registerKey("estimate.promote", KEY, WORKORDER_ID);

            assertThat(savedKey().getKeyValue()).isEqualTo("estimate.promote:" + KEY);
        }
    }

    @Nested
    @DisplayName("change-request keys")
    class ChangeRequestKeys {

        @Test
        void replaysTheChangeRequestBehindALiveKey() {
            IdempotencyKey stored = key(NOT_EXPIRED);
            stored.setChangeRequestId(CHANGE_REQUEST_ID);
            stubKey("change-request.default:" + KEY, stored);

            assertThat(service.getExistingChangeRequestId(KEY)).contains(CHANGE_REQUEST_ID);
            assertThat(service.getExistingChangeRequestId("change-request.default", KEY))
                    .contains(CHANGE_REQUEST_ID);
        }

        @Test
        void ignoresALiveKeyThatCarriesNoChangeRequest() {
            stubKey("change-request.default:" + KEY, key(NOT_EXPIRED));

            assertThat(service.getExistingChangeRequestId(KEY)).isEmpty();
        }

        @Test
        void ignoresAnExpiredKey() {
            IdempotencyKey stored = key(EXPIRED);
            stored.setChangeRequestId(CHANGE_REQUEST_ID);
            stubKey("change-request.default:" + KEY, stored);

            assertThat(service.getExistingChangeRequestId(KEY)).isEmpty();
        }

        @Test
        void marksTheKeyProcessedForTheChangeRequest() {
            service.markKeyProcessedForChangeRequest(KEY, CHANGE_REQUEST_ID);

            IdempotencyKey saved = savedKey();
            assertThat(saved.getKeyValue()).isEqualTo("change-request.default:" + KEY);
            assertThat(saved.getChangeRequestId()).isEqualTo(CHANGE_REQUEST_ID);
            assertThat(saved.getWorkorderId()).isNull();
            assertThat(saved.getExpiresAt()).isEqualTo(EXPECTED_EXPIRY);
        }

        @Test
        void marksAnOperationScopedKeyProcessed() {
            service.markKeyProcessedForChangeRequest("change-request.create", KEY, CHANGE_REQUEST_ID);

            assertThat(savedKey().getKeyValue()).isEqualTo("change-request.create:" + KEY);
        }
    }

    @Nested
    @DisplayName("labor keys")
    class LaborKeys {

        @Test
        void replaysTheLaborEntryBehindALiveKey() {
            IdempotencyKey stored = key(NOT_EXPIRED);
            stored.setLaborEntryId(LABOR_ENTRY_ID);
            stubKey("labor.default:" + KEY, stored);

            assertThat(service.getExistingLaborEntryId(KEY)).contains(LABOR_ENTRY_ID);
            assertThat(service.getExistingLaborEntryId("labor.default", KEY)).contains(LABOR_ENTRY_ID);
        }

        @Test
        void ignoresAnExpiredOrEmptyKey() {
            IdempotencyKey expired = key(EXPIRED);
            expired.setLaborEntryId(LABOR_ENTRY_ID);
            stubKey("labor.default:" + KEY, expired);
            assertThat(service.getExistingLaborEntryId(KEY)).isEmpty();

            stubKey("labor.default:" + KEY, key(NOT_EXPIRED));
            assertThat(service.getExistingLaborEntryId(KEY)).isEmpty();
        }

        @Test
        void registersTheLaborKeyWithItsCreationStamp() {
            service.registerLaborKey(KEY, LABOR_ENTRY_ID);

            IdempotencyKey saved = savedKey();
            assertThat(saved.getKeyValue()).isEqualTo("labor.default:" + KEY);
            assertThat(saved.getLaborEntryId()).isEqualTo(LABOR_ENTRY_ID);
            assertThat(saved.getCreatedAt()).isEqualTo(NOW);
            assertThat(saved.getExpiresAt()).isEqualTo(EXPECTED_EXPIRY);
        }

        @Test
        void registersAnOperationScopedLaborKey() {
            service.registerLaborKey("labor.log", KEY, LABOR_ENTRY_ID);

            assertThat(savedKey().getKeyValue()).isEqualTo("labor.log:" + KEY);
        }
    }

    @Nested
    @DisplayName("part usage, part adjustment and invoice keys")
    class OtherKeys {

        @Test
        void replaysThePartUsageEventBehindALiveKey() {
            IdempotencyKey stored = key(NOT_EXPIRED);
            stored.setPartUsageEventId(PART_USAGE_ID);
            stubKey("part-usage.default:" + KEY, stored);

            assertThat(service.getExistingPartUsageEventId(KEY)).contains(PART_USAGE_ID);
            assertThat(service.getExistingPartUsageEventId("part-usage.default", KEY))
                    .contains(PART_USAGE_ID);
        }

        @Test
        void ignoresAnExpiredOrEmptyPartUsageKey() {
            IdempotencyKey expired = key(EXPIRED);
            expired.setPartUsageEventId(PART_USAGE_ID);
            stubKey("part-usage.default:" + KEY, expired);
            assertThat(service.getExistingPartUsageEventId(KEY)).isEmpty();

            stubKey("part-usage.default:" + KEY, key(NOT_EXPIRED));
            assertThat(service.getExistingPartUsageEventId(KEY)).isEmpty();
        }

        @Test
        void marksTheKeyProcessedForPartUsage() {
            service.markKeyProcessedForPartUsage(KEY, PART_USAGE_ID);

            IdempotencyKey saved = savedKey();
            assertThat(saved.getKeyValue()).isEqualTo("part-usage.default:" + KEY);
            assertThat(saved.getPartUsageEventId()).isEqualTo(PART_USAGE_ID);
        }

        @Test
        void marksAnOperationScopedPartUsageKey() {
            service.markKeyProcessedForPartUsage("part-usage.record", KEY, PART_USAGE_ID);

            assertThat(savedKey().getKeyValue()).isEqualTo("part-usage.record:" + KEY);
        }

        @Test
        void replaysThePartAdjustmentEventBehindALiveKey() {
            IdempotencyKey stored = key(NOT_EXPIRED);
            stored.setPartAdjustmentEventId(PART_ADJUSTMENT_ID);
            stubKey("part-adjustment.default:" + KEY, stored);

            assertThat(service.getExistingPartAdjustmentEventId(KEY)).contains(PART_ADJUSTMENT_ID);
            assertThat(service.getExistingPartAdjustmentEventId("part-adjustment.default", KEY))
                    .contains(PART_ADJUSTMENT_ID);
        }

        @Test
        void ignoresAnExpiredOrEmptyPartAdjustmentKey() {
            IdempotencyKey expired = key(EXPIRED);
            expired.setPartAdjustmentEventId(PART_ADJUSTMENT_ID);
            stubKey("part-adjustment.default:" + KEY, expired);
            assertThat(service.getExistingPartAdjustmentEventId(KEY)).isEmpty();

            stubKey("part-adjustment.default:" + KEY, key(NOT_EXPIRED));
            assertThat(service.getExistingPartAdjustmentEventId(KEY)).isEmpty();
        }

        @Test
        void marksTheKeyProcessedForAPartAdjustment() {
            service.markKeyProcessedForPartAdjustment(KEY, PART_ADJUSTMENT_ID);

            IdempotencyKey saved = savedKey();
            assertThat(saved.getKeyValue()).isEqualTo("part-adjustment.default:" + KEY);
            assertThat(saved.getPartAdjustmentEventId()).isEqualTo(PART_ADJUSTMENT_ID);
        }

        @Test
        void marksAnOperationScopedPartAdjustmentKey() {
            service.markKeyProcessedForPartAdjustment("part-adjustment.post", KEY, PART_ADJUSTMENT_ID);

            assertThat(savedKey().getKeyValue()).isEqualTo("part-adjustment.post:" + KEY);
        }

        @Test
        void replaysTheInvoiceBehindALiveKey() {
            IdempotencyKey stored = key(NOT_EXPIRED);
            stored.setInvoiceId(INVOICE_ID);
            stubKey("invoice.default:" + KEY, stored);

            assertThat(service.getExistingInvoiceId(KEY)).contains(INVOICE_ID);
            assertThat(service.getExistingInvoiceId("invoice.default", KEY)).contains(INVOICE_ID);
        }

        @Test
        void ignoresAnExpiredOrEmptyInvoiceKey() {
            IdempotencyKey expired = key(EXPIRED);
            expired.setInvoiceId(INVOICE_ID);
            stubKey("invoice.default:" + KEY, expired);
            assertThat(service.getExistingInvoiceId(KEY)).isEmpty();

            stubKey("invoice.default:" + KEY, key(NOT_EXPIRED));
            assertThat(service.getExistingInvoiceId(KEY)).isEmpty();
        }

        @Test
        void registersTheInvoiceKey() {
            service.registerInvoiceKey(KEY, INVOICE_ID);

            IdempotencyKey saved = savedKey();
            assertThat(saved.getKeyValue()).isEqualTo("invoice.default:" + KEY);
            assertThat(saved.getInvoiceId()).isEqualTo(INVOICE_ID);
            assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        }

        @Test
        void registersAnOperationScopedInvoiceKey() {
            service.registerInvoiceKey("invoice.generate", KEY, INVOICE_ID);

            assertThat(savedKey().getKeyValue()).isEqualTo("invoice.generate:" + KEY);
        }
    }

    @Test
    void reportsHowManyExpiredKeysWereRemoved() {
        when(repository.deleteExpiredKeys(NOW)).thenReturn(7);

        assertThat(service.cleanupExpiredKeys()).isEqualTo(7);
        verify(repository).deleteExpiredKeys(NOW);
    }

    @Test
    void neverConfusesKeysAcrossEntityTypes() {
        IdempotencyKey workorderOnly = key(NOT_EXPIRED);
        workorderOnly.setWorkorderId(WORKORDER_ID);
        when(repository.findByKeyValue(any())).thenReturn(Optional.of(workorderOnly));

        assertThat(service.getExistingWorkorderId(KEY)).contains(WORKORDER_ID);
        assertThat(service.getExistingChangeRequestId(KEY)).isEmpty();
        assertThat(service.getExistingLaborEntryId(KEY)).isEmpty();
        assertThat(service.getExistingPartUsageEventId(KEY)).isEmpty();
        assertThat(service.getExistingPartAdjustmentEventId(KEY)).isEmpty();
        assertThat(service.getExistingInvoiceId(KEY)).isEmpty();
    }
}
