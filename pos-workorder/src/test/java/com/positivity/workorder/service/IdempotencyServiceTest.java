package com.positivity.workorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.positivity.workorder.internal.entity.IdempotencyKey;
import com.positivity.workorder.internal.repository.IdempotencyKeyRepository;
import com.positivity.workorder.internal.service.IdempotencyServiceImpl;

/**
 * Unit tests for IdempotencyService
 * 
 * Tests idempotency key management including registration,
 * validation, and cleanup of expired keys.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyService Unit Tests")
class IdempotencyServiceTest {

    @Mock
    private IdempotencyKeyRepository repository;

    @InjectMocks
    private IdempotencyServiceImpl service;

    private String testKeyValue;
    private UUID testWorkorderId;

    @BeforeEach
    void setUp() {
        testKeyValue = "test-key-" + UUID.randomUUID();
        testWorkorderId = UUID.randomUUID();
    }

    // ===== GET EXISTING WORKORDER ID TESTS =====

    @Test
    @DisplayName("getExistingWorkorderId - returns workorder ID for existing non-expired key")
    void getExistingWorkorderId_existingNotExpired_returnsWorkorderId() {
        // Arrange
        Instant futureExpiry = Instant.now().plus(1, ChronoUnit.HOURS);
        IdempotencyKey key = new IdempotencyKey(testKeyValue, testWorkorderId, futureExpiry);
        when(repository.findByKeyValue(testKeyValue)).thenReturn(Optional.of(key));

        // Act
        Optional<UUID> result = service.getExistingWorkorderId(testKeyValue);

        // Assert
        assertThat(result).isPresent().contains(testWorkorderId);
        verify(repository).findByKeyValue(testKeyValue);
    }

    @Test
    @DisplayName("getExistingWorkorderId - returns empty for expired key")
    void getExistingWorkorderId_expired_returnsEmpty() {
        // Arrange
        Instant pastExpiry = Instant.now().minus(1, ChronoUnit.HOURS);
        IdempotencyKey key = new IdempotencyKey(testKeyValue, testWorkorderId, pastExpiry);
        when(repository.findByKeyValue(testKeyValue)).thenReturn(Optional.of(key));

        // Act
        Optional<UUID> result = service.getExistingWorkorderId(testKeyValue);

        // Assert
        assertThat(result).isEmpty();
        verify(repository).findByKeyValue(testKeyValue);
    }

    @Test
    @DisplayName("getExistingWorkorderId - returns empty for non-existent key")
    void getExistingWorkorderId_notFound_returnsEmpty() {
        // Arrange
        when(repository.findByKeyValue(testKeyValue)).thenReturn(Optional.empty());

        // Act
        Optional<UUID> result = service.getExistingWorkorderId(testKeyValue);

        // Assert
        assertThat(result).isEmpty();
        verify(repository).findByKeyValue(testKeyValue);
    }

    // ===== REGISTER KEY TESTS =====

    @Test
    @DisplayName("registerKey - saves new key with 24-hour expiration")
    void registerKey_success() {
        // Arrange
        when(repository.save(any(IdempotencyKey.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        service.registerKey(testKeyValue, testWorkorderId);

        // Assert
        verify(repository).save(any(IdempotencyKey.class));
    }

    @Test
    @DisplayName("registerKey - sets expiration to 24 hours in future")
    void registerKey_setsCorrectExpiration() {
        // Arrange
        Instant beforeCall = Instant.now();
        when(repository.save(any(IdempotencyKey.class))).thenAnswer(invocation -> {
            IdempotencyKey saved = invocation.getArgument(0);
            Instant expectedExpiry = beforeCall.plus(24, ChronoUnit.HOURS);

            // Allow 1 second tolerance for test execution time
            assertThat(saved.getExpiresAt()).isBetween(
                    expectedExpiry.minus(1, ChronoUnit.SECONDS),
                    expectedExpiry.plus(1, ChronoUnit.SECONDS));
            assertThat(saved.getKeyValue()).isEqualTo(testKeyValue);
            assertThat(saved.getWorkorderId()).isEqualTo(testWorkorderId);
            return saved;
        });

        // Act
        service.registerKey(testKeyValue, testWorkorderId);

        // Assert
        verify(repository).save(any(IdempotencyKey.class));
    }

    // ===== CLEANUP TESTS =====

    @Test
    @DisplayName("cleanupExpiredKeys - deletes expired keys and returns count")
    void cleanupExpiredKeys_success() {
        // Arrange
        int deletedCount = 5;
        when(repository.deleteExpiredKeys(any(Instant.class))).thenReturn(deletedCount);

        // Act
        int result = service.cleanupExpiredKeys();

        // Assert
        assertThat(result).isEqualTo(deletedCount);
        verify(repository).deleteExpiredKeys(any(Instant.class));
    }

    @Test
    @DisplayName("cleanupExpiredKeys - returns zero when no expired keys")
    void cleanupExpiredKeys_noExpiredKeys_returnsZero() {
        // Arrange
        when(repository.deleteExpiredKeys(any(Instant.class))).thenReturn(0);

        // Act
        int result = service.cleanupExpiredKeys();

        // Assert
        assertThat(result).isZero();
        verify(repository).deleteExpiredKeys(any(Instant.class));
    }

    @Test
    @DisplayName("cleanupExpiredKeys - passes current time to repository")
    void cleanupExpiredKeys_passesCurrentTime() {
        // Arrange
        Instant beforeCall = Instant.now();
        when(repository.deleteExpiredKeys(any(Instant.class))).thenAnswer(invocation -> {
            Instant passedTime = invocation.getArgument(0);
            // Allow 1 second tolerance
            assertThat(passedTime).isBetween(
                    beforeCall.minus(1, ChronoUnit.SECONDS),
                    Instant.now().plus(1, ChronoUnit.SECONDS));
            return 0;
        });

        // Act
        service.cleanupExpiredKeys();

        // Assert
        verify(repository).deleteExpiredKeys(any(Instant.class));
    }
}
