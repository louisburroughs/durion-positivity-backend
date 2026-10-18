package com.positivity.inboundmail.internal.service;

import com.positivity.inboundmail.internal.dto.IssuedAddressResponse;
import com.positivity.inboundmail.internal.entity.InboundAddress;
import com.positivity.inboundmail.internal.enums.AddressStatus;
import com.positivity.inboundmail.internal.repository.InboundAddressRepository;
import com.positivity.tenancy.TenantResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issue and rotation run in short transactions of their own ({@link TransactionTemplate}) so a lost
 * race on a unique index (the one-active-address index, or the per-request key) can be re-read in a
 * fresh transaction: a failed insert poisons the transaction it ran in on Postgres. Only the address
 * id is ever logged, never the address text (ADR-0072).
 */
@Slf4j
@Service
public class AddressRegistryImpl implements AddressRegistry {

    /** A rotation that loses to a concurrent one by another request retries against the new state. */
    private static final int MAX_ATTEMPTS = 3;

    private final InboundAddressRepository addresses;
    private final AddressGenerator generator;
    private final TenantResolver tenantResolver;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public AddressRegistryImpl(
            InboundAddressRepository addresses,
            AddressGenerator generator,
            TenantResolver tenantResolver,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.addresses = addresses;
        this.generator = generator;
        this.tenantResolver = tenantResolver;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public @NonNull IssuedAddressResponse issueOrGet() {
        UUID tenantId = tenantResolver.require();
        DataIntegrityViolationException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return Objects.requireNonNull(transaction.execute(status -> addresses
                        .findByTenantIdAndStatus(tenantId, AddressStatus.ACTIVE)
                        .map(AddressRegistryImpl::toResponse)
                        .orElseGet(() -> toResponse(issue(tenantId, null)))));
            } catch (DataIntegrityViolationException raced) {
                // A concurrent first request won the one-active-address index; the next pass reads its row.
                last = raced;
                log.debug("Address issue lost a race for the tenant; re-reading (attempt {})", attempt);
            }
        }
        // Not a race that resolves (for example the address text itself collided): surface the real cause.
        throw last;
    }

    @Override
    public @NonNull IssuedAddressResponse rotate(@NonNull UUID requestId) {
        UUID tenantId = tenantResolver.require();
        DataIntegrityViolationException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return Objects.requireNonNull(transaction.execute(status -> rotateInTransaction(tenantId, requestId)));
            } catch (DataIntegrityViolationException raced) {
                // Same request id raced with itself (answered from its row on the next pass), or another
                // rotation retired the row under us (the next pass rotates the new active one).
                last = raced;
                log.debug("Address rotation lost a race for the tenant; retrying (attempt {})", attempt);
            }
        }
        throw last;
    }

    private IssuedAddressResponse rotateInTransaction(UUID tenantId, UUID requestId) {
        Optional<InboundAddress> replay = addresses.findByTenantIdAndIssuedByRequestId(tenantId, requestId);
        if (replay.isPresent()) {
            return toResponse(replay.get());
        }
        Optional<InboundAddress> active = addresses.findByTenantIdAndStatus(tenantId, AddressStatus.ACTIVE);
        if (active.isPresent()) {
            InboundAddress retiring = active.get();
            retiring.setStatus(AddressStatus.RETIRED);
            retiring.setRetiredAt(Instant.now(clock));
            // Flushed first: Hibernate orders inserts before updates, and the partial unique index allows
            // one ACTIVE row per tenant at every statement.
            addresses.saveAndFlush(retiring);
            log.info("Retired intake address addressId={}", retiring.getAddressId());
        }
        return toResponse(issue(tenantId, requestId));
    }

    private InboundAddress issue(UUID tenantId, UUID requestId) {
        InboundAddress created = addresses.saveAndFlush(InboundAddress.builder()
                .address(generator.generate())
                .tenantId(tenantId)
                .status(AddressStatus.ACTIVE)
                .issuedByRequestId(requestId)
                .build());
        log.info("Issued intake address addressId={}", created.getAddressId());
        return created;
    }

    private static IssuedAddressResponse toResponse(InboundAddress address) {
        // Truncated to the column's precision so the response of the issuing call and a later re-read agree.
        return new IssuedAddressResponse(
                address.getAddressId(),
                address.getAddress(),
                address.getCreatedAt().truncatedTo(ChronoUnit.MICROS));
    }
}
