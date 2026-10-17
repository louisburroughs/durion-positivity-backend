package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.IdempotencyKey;
import com.positivity.accounting.internal.repository.IdempotencyKeyRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for IdempotencyService: registration and the presence check. Posting keys never expire (#2595).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyService Unit Tests")
class IdempotencyServiceTest {

    private static final java.time.Clock TEST_CLOCK =
            java.time.Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), java.time.ZoneOffset.UTC);

    @Mock
    private IdempotencyKeyRepository repository;

    private IdempotencyServiceImpl service;

    private String testKeyValue;
    private UUID testInvoiceId;

    @BeforeEach
    void setUp() {
        service = new IdempotencyServiceImpl(repository, TEST_CLOCK);
        testKeyValue = "test-key-" + UUID.fromString("00000000-0000-0000-0000-000000000001");
        testInvoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    }

    @Test
    @DisplayName("isKeyProcessed - a registered key is processed")
    void isKeyProcessed_registered_returnsTrue() {
        IdempotencyKey key = new IdempotencyKey(testKeyValue, testInvoiceId);
        when(repository.findByKeyValue(testKeyValue)).thenReturn(Optional.of(key));

        assertThat(service.isKeyProcessed(testKeyValue)).isTrue();
        verify(repository).findByKeyValue(testKeyValue);
    }

    @Test
    @DisplayName("#2595: a key registered with an expiry before V28, long past, is still processed")
    void isKeyProcessed_legacyExpiredKey_stillProcessed() {
        IdempotencyKey legacy = new IdempotencyKey(testKeyValue, testInvoiceId);
        legacy.setExpiresAt(Instant.parse("2024-01-01T00:00:00Z"));
        when(repository.findByKeyValue(testKeyValue)).thenReturn(Optional.of(legacy));

        assertThat(service.isKeyProcessed(testKeyValue))
                .as("a redelivery under a fresh eventId days later posts nothing twice")
                .isTrue();
    }

    @Test
    @DisplayName("isKeyProcessed - returns false for non-existent key")
    void isKeyProcessed_notFound_returnsFalse() {
        when(repository.findByKeyValue(testKeyValue)).thenReturn(Optional.empty());

        assertThat(service.isKeyProcessed(testKeyValue)).isFalse();
        verify(repository).findByKeyValue(testKeyValue);
    }

    @Test
    @DisplayName("REST window: a windowed key is active inside its window and inactive after it (#2207)")
    void windowedKeyExpires() {
        IdempotencyKey inside = new IdempotencyKey(testKeyValue, testInvoiceId);
        inside.setExpiresAt(Instant.now(TEST_CLOCK).plusSeconds(60));
        when(repository.findByKeyValue(testKeyValue)).thenReturn(Optional.of(inside));
        assertThat(service.isKeyActive(testKeyValue)).isTrue();

        IdempotencyKey past = new IdempotencyKey(testKeyValue, testInvoiceId);
        past.setExpiresAt(Instant.now(TEST_CLOCK).minusSeconds(1));
        when(repository.findByKeyValue(testKeyValue)).thenReturn(Optional.of(past));
        assertThat(service.isKeyActive(testKeyValue)).isFalse();
        assertThat(service.isKeyProcessed(testKeyValue))
                .as("a posting key's presence check ignores any expiry")
                .isTrue();
    }

    @Test
    @DisplayName("registerKey with a window stores its end (#2207)")
    void registerKey_withWindow_storesExpiry() {
        when(repository.saveAndFlush(any(IdempotencyKey.class))).thenAnswer(invocation -> {
            IdempotencyKey saved = invocation.getArgument(0);
            assertThat(saved.getExpiresAt()).isEqualTo(Instant.now(TEST_CLOCK).plus(java.time.Duration.ofHours(24)));
            return saved;
        });

        service.registerKey(testKeyValue, testInvoiceId, java.time.Duration.ofHours(24));

        verify(repository).saveAndFlush(any(IdempotencyKey.class));
    }

    @Test
    @DisplayName("registerKey - saves the key without an expiry (#2595)")
    void registerKey_storesNoExpiry() {
        when(repository.saveAndFlush(any(IdempotencyKey.class))).thenAnswer(invocation -> {
            IdempotencyKey saved = invocation.getArgument(0);
            assertThat(saved.getExpiresAt()).isNull();
            assertThat(saved.getKeyValue()).isEqualTo(testKeyValue);
            assertThat(saved.getInvoiceId()).isEqualTo(testInvoiceId);
            return saved;
        });

        service.registerKey(testKeyValue, testInvoiceId);

        verify(repository).saveAndFlush(any(IdempotencyKey.class));
    }
}
