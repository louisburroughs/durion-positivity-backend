package com.positivity.inboundmail.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inboundmail.internal.dto.IssuedAddressResponse;
import com.positivity.inboundmail.internal.entity.InboundAddress;
import com.positivity.inboundmail.internal.enums.AddressStatus;
import com.positivity.inboundmail.internal.repository.InboundAddressRepository;
import com.positivity.tenancy.TenantResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

@DisplayName("AddressRegistryImpl: issue, rotation and the races on the unique indexes")
class AddressRegistryImplTest {

    private static final UUID TENANT = UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    private final InboundAddressRepository repository = mock(InboundAddressRepository.class);
    private final AddressGenerator generator = mock(AddressGenerator.class);
    private final TenantResolver resolver = mock(TenantResolver.class);
    private AddressRegistryImpl registry;

    @BeforeEach
    void setUp() {
        when(resolver.require()).thenReturn(TENANT);
        when(generator.generate()).thenReturn("bills-aaaaaaaaaaaaaaaaaaaaaaaaaa@mail.example.com");
        when(repository.saveAndFlush(any(InboundAddress.class))).thenAnswer(invocation -> {
            InboundAddress saved = invocation.getArgument(0);
            if (saved.getAddressId() == null) {
                saved.setAddressId(UUID.randomUUID());
                saved.setCreatedAt(NOW);
            }
            return saved;
        });
        registry = new AddressRegistryImpl(
                repository,
                generator,
                resolver,
                Clock.fixed(NOW, ZoneOffset.UTC),
                mock(PlatformTransactionManager.class));
    }

    private static InboundAddress existing(AddressStatus status, UUID requestId) {
        return InboundAddress.builder()
                .addressId(UUID.randomUUID())
                .address("bills-bbbbbbbbbbbbbbbbbbbbbbbbbb@mail.example.com")
                .tenantId(TENANT)
                .status(status)
                .issuedByRequestId(requestId)
                .createdAt(NOW.minusSeconds(60))
                .build();
    }

    @Test
    @DisplayName("an existing ACTIVE address is returned and nothing is issued")
    void returnsTheActiveAddress() {
        InboundAddress active = existing(AddressStatus.ACTIVE, null);
        when(repository.findByTenantIdAndStatus(TENANT, AddressStatus.ACTIVE)).thenReturn(Optional.of(active));

        IssuedAddressResponse response = registry.issueOrGet();

        assertThat(response.addressId()).isEqualTo(active.getAddressId());
        assertThat(response.address()).isEqualTo(active.getAddress());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("with no address, one is issued ACTIVE for the bound tenant")
    void issuesWhenNone() {
        when(repository.findByTenantIdAndStatus(TENANT, AddressStatus.ACTIVE)).thenReturn(Optional.empty());

        IssuedAddressResponse response = registry.issueOrGet();

        assertThat(response.address()).isEqualTo("bills-aaaaaaaaaaaaaaaaaaaaaaaaaa@mail.example.com");
        assertThat(response.issuedAt()).isEqualTo(NOW);
        verify(repository).saveAndFlush(any(InboundAddress.class));
    }

    @Test
    @DisplayName("a concurrent first request that loses the one-active index re-reads the winner's row")
    void loserReadsTheWinner() {
        InboundAddress winner = existing(AddressStatus.ACTIVE, null);
        when(repository.findByTenantIdAndStatus(TENANT, AddressStatus.ACTIVE))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        doThrow(new DataIntegrityViolationException("dup")).when(repository).saveAndFlush(any(InboundAddress.class));

        IssuedAddressResponse response = registry.issueOrGet();

        assertThat(response.addressId()).isEqualTo(winner.getAddressId());
    }

    @Test
    @DisplayName("rotation retires the ACTIVE row first, then issues the new one under the request id")
    void rotatesRetiringFirst() {
        UUID requestId = UUID.randomUUID();
        InboundAddress active = existing(AddressStatus.ACTIVE, null);
        when(repository.findByTenantIdAndIssuedByRequestId(TENANT, requestId)).thenReturn(Optional.empty());
        when(repository.findByTenantIdAndStatus(TENANT, AddressStatus.ACTIVE)).thenReturn(Optional.of(active));

        IssuedAddressResponse response = registry.rotate(requestId);

        assertThat(active.getStatus()).isEqualTo(AddressStatus.RETIRED);
        assertThat(active.getRetiredAt()).isEqualTo(NOW);
        assertThat(response.addressId()).isNotEqualTo(active.getAddressId());
        InOrder order = inOrder(repository);
        order.verify(repository).saveAndFlush(active);
        order.verify(repository).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                (InboundAddress a) -> a.getStatus() == AddressStatus.ACTIVE && requestId.equals(a.getIssuedByRequestId())));
    }

    @Test
    @DisplayName("a replayed request id returns the address it issued and rotates nothing, even if since retired")
    void replayIsIdempotent() {
        UUID requestId = UUID.randomUUID();
        InboundAddress issuedEarlier = existing(AddressStatus.RETIRED, requestId);
        when(repository.findByTenantIdAndIssuedByRequestId(TENANT, requestId)).thenReturn(Optional.of(issuedEarlier));

        IssuedAddressResponse response = registry.rotate(requestId);

        assertThat(response.addressId()).isEqualTo(issuedEarlier.getAddressId());
        verify(repository, never()).saveAndFlush(any());
        verify(repository, never()).findByTenantIdAndStatus(eq(TENANT), any());
    }

    @Test
    @DisplayName("a rotation that races with its own replay answers from the row the other attempt wrote")
    void rotationRaceResolvesToTheRequestsRow() {
        UUID requestId = UUID.randomUUID();
        InboundAddress winner = existing(AddressStatus.ACTIVE, requestId);
        when(repository.findByTenantIdAndIssuedByRequestId(TENANT, requestId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(repository.findByTenantIdAndStatus(TENANT, AddressStatus.ACTIVE)).thenReturn(Optional.empty());
        doThrow(new DataIntegrityViolationException("dup")).when(repository).saveAndFlush(any(InboundAddress.class));

        assertThat(registry.rotate(requestId).addressId()).isEqualTo(winner.getAddressId());
    }
}
