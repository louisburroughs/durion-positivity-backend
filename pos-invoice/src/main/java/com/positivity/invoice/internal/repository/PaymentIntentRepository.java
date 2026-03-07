package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.PaymentIntent;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link PaymentIntent} entities.
 * Story #9.
 */
public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, UUID> {

    /**
     * Looks up a payment intent by its client-provided idempotency key.
     *
     * <p>
     * Used to implement safe-retry semantics: if a key is found, the previous
     * result is returned without re-calling the gateway (AC4, Story #9).
     *
     * @param idempotencyKey the client idempotency key
     * @return the existing payment intent, or empty if not found
     */
    Optional<PaymentIntent> findByIdempotencyKey(@NonNull String idempotencyKey);
}
