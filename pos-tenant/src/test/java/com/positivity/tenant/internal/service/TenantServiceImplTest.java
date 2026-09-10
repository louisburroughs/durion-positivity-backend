package com.positivity.tenant.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.tenant.internal.dto.TenantCreateRequest;
import com.positivity.tenant.internal.dto.TenantResponse;
import com.positivity.tenant.internal.dto.TenantUpdateRequest;
import com.positivity.tenant.internal.entity.TenantEntity;
import com.positivity.tenant.internal.enums.TenantStatus;
import com.positivity.tenant.internal.exception.DuplicateResourceException;
import com.positivity.tenant.internal.exception.InvalidStatusTransitionException;
import com.positivity.tenant.internal.exception.ResourceNotFoundException;
import com.positivity.tenant.internal.repository.AccountRepository;
import com.positivity.tenant.internal.repository.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

/** The registry's status machine and the fact it publishes on each move (ADR-0062 §7). */
class TenantServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
    private static final UUID ACCOUNT = UUID.fromString("01990000-0000-7000-8000-00000000a001");

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final TenantFactPublisher facts = mock(TenantFactPublisher.class);

    private TenantServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TenantServiceImpl(tenants, accounts, facts, Clock.fixed(NOW, ZoneOffset.UTC));
        when(tenants.saveAndFlush(any())).thenAnswer(invocation -> {
            TenantEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(UUID.randomUUID());
            }
            return entity;
        });
    }

    private TenantCreateRequest createRequest() {
        return TenantCreateRequest.builder()
                .slug("acme")
                .displayName("Acme")
                .accountId(ACCOUNT)
                .cell("us-east-1")
                .initialAdminEmail("owner@acme.example")
                .build();
    }

    private TenantEntity existing(TenantStatus status) {
        TenantEntity entity = TenantEntity.builder()
                .id(UUID.randomUUID())
                .slug("acme")
                .displayName("Acme")
                .status(status)
                .accountId(ACCOUNT)
                .initialAdminEmail("owner@acme.example")
                .version(3L)
                .build();
        when(tenants.findById(entity.getId())).thenReturn(Optional.of(entity));
        return entity;
    }

    @Test
    @DisplayName("create registers PENDING and publishes tenant.created")
    void createPublishesCreated() {
        when(accounts.existsById(ACCOUNT)).thenReturn(true);
        when(tenants.existsBySlug("acme")).thenReturn(false);

        TenantResponse response = service.create(createRequest());

        assertThat(response.getStatus()).isEqualTo(TenantStatus.PENDING);
        assertThat(response.getSlug()).isEqualTo("acme");
        assertThat(response.getInitialAdminEmail()).isEqualTo("owner@acme.example");
        ArgumentCaptor<TenantEntity> saved = ArgumentCaptor.forClass(TenantEntity.class);
        verify(facts).tenantCreated(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(response.getId());
    }

    @Test
    void createRejectsUnknownAccountAndTakenSlug() {
        when(accounts.existsById(ACCOUNT)).thenReturn(false);
        assertThatThrownBy(() -> service.create(createRequest())).isInstanceOf(ResourceNotFoundException.class);

        when(accounts.existsById(ACCOUNT)).thenReturn(true);
        when(tenants.existsBySlug("acme")).thenReturn(true);
        assertThatThrownBy(() -> service.create(createRequest())).isInstanceOf(DuplicateResourceException.class);
        verifyNoInteractions(facts);
    }

    @Test
    @DisplayName("a slug race lost at the unique constraint is a 409, not a 500")
    void createMapsConstraintViolationToConflict() {
        when(accounts.existsById(ACCOUNT)).thenReturn(true);
        when(tenants.existsBySlug("acme")).thenReturn(false);
        doThrow(new DataIntegrityViolationException("tenant_slug_key"))
                .when(tenants)
                .saveAndFlush(any());

        assertThatThrownBy(() -> service.create(createRequest())).isInstanceOf(DuplicateResourceException.class);
        verifyNoInteractions(facts);
    }

    @Test
    @DisplayName("any other integrity failure surfaces as itself, never as a slug conflict")
    void createRethrowsOtherIntegrityFailures() {
        when(accounts.existsById(ACCOUNT)).thenReturn(true);
        when(tenants.existsBySlug("acme")).thenReturn(false);
        doThrow(new DataIntegrityViolationException("null value in column \"display_name\""))
                .when(tenants)
                .saveAndFlush(any());

        assertThatThrownBy(() -> service.create(createRequest())).isInstanceOf(DataIntegrityViolationException.class);
        verifyNoInteractions(facts);
    }

    @Test
    @DisplayName("the slug constraint is recognised anywhere in the cause chain")
    void slugCollisionIsDetectedThroughTheCauseChain() {
        DataIntegrityViolationException nested = new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("duplicate key value violates unique constraint \"tenant_slug_key\""));
        assertThat(TenantServiceImpl.isSlugCollision(nested)).isTrue();
        assertThat(TenantServiceImpl.isSlugCollision(new DataIntegrityViolationException("fk_tenant_account")))
                .isFalse();
    }

    @Test
    void updateChangesDescriptorsAndPublishesUpdated() {
        TenantEntity tenant = existing(TenantStatus.ACTIVE);

        TenantResponse response = service.update(
                tenant.getId(),
                TenantUpdateRequest.builder().displayName("Acme Tire").build());

        assertThat(response.getDisplayName()).isEqualTo("Acme Tire");
        verify(facts).tenantUpdated(tenant);
    }

    @Test
    @DisplayName("an update that changes nothing publishes nothing")
    void updateWithoutChangeIsSilent() {
        TenantEntity tenant = existing(TenantStatus.ACTIVE);

        service.update(
                tenant.getId(),
                TenantUpdateRequest.builder().displayName("Acme").build());

        verify(tenants, never()).saveAndFlush(any());
        verifyNoInteractions(facts);
    }

    @Test
    void updateRefusesDecommissionedTenant() {
        TenantEntity tenant = existing(TenantStatus.DECOMMISSIONED);
        assertThatThrownBy(() -> service.update(
                        tenant.getId(),
                        TenantUpdateRequest.builder().displayName("x").build()))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void suspendStampsTimestampAndPublishesSuspended() {
        TenantEntity tenant = existing(TenantStatus.ACTIVE);

        TenantResponse response = service.suspend(tenant.getId());

        assertThat(response.getStatus()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(response.getSuspendedAt()).isEqualTo(NOW);
        verify(facts).tenantSuspended(tenant);
    }

    @Test
    void suspendRefusesPendingTenant() {
        TenantEntity tenant = existing(TenantStatus.PENDING);
        assertThatThrownBy(() -> service.suspend(tenant.getId())).isInstanceOf(InvalidStatusTransitionException.class);
        verifyNoInteractions(facts);
    }

    @Test
    void reactivateOnlyFromSuspended() {
        TenantEntity suspended = existing(TenantStatus.SUSPENDED);
        assertThat(service.reactivate(suspended.getId()).getStatus()).isEqualTo(TenantStatus.ACTIVE);
        verify(facts).tenantReactivated(suspended);

        TenantEntity pending = existing(TenantStatus.PENDING);
        assertThatThrownBy(() -> service.reactivate(pending.getId()))
                .as("PENDING -> ACTIVE belongs to provisioning, never to an operator")
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void decommissionIsTerminal() {
        TenantEntity tenant = existing(TenantStatus.SUSPENDED);

        TenantResponse response = service.decommission(tenant.getId());

        assertThat(response.getStatus()).isEqualTo(TenantStatus.DECOMMISSIONED);
        assertThat(response.getDecommissionedAt()).isEqualTo(NOW);
        verify(facts).tenantDecommissioned(tenant);
        assertThatThrownBy(() -> service.decommission(tenant.getId()))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    @DisplayName("tenant.provisioned activates a PENDING tenant and publishes tenant.updated")
    void markProvisionedActivates() {
        TenantEntity tenant = existing(TenantStatus.PENDING);

        service.markProvisioned(tenant.getId());

        assertThat(tenant.getStatus()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(tenant.getActivatedAt()).isEqualTo(NOW);
        verify(facts).tenantUpdated(tenant);
    }

    @Test
    @DisplayName("tenant.provisioned is idempotent: ACTIVE, SUSPENDED and unknown tenants are left alone")
    void markProvisionedIsIdempotent() {
        TenantEntity active = existing(TenantStatus.ACTIVE);
        service.markProvisioned(active.getId());
        service.markProvisioned(UUID.randomUUID());

        verify(tenants, never()).saveAndFlush(any());
        verifyNoInteractions(facts);
    }

    @Test
    void listFiltersByStatus() {
        TenantEntity active = existing(TenantStatus.ACTIVE);
        TenantEntity pending = existing(TenantStatus.PENDING);
        when(tenants.findByStatusOrderByCreatedAtAsc(TenantStatus.ACTIVE)).thenReturn(List.of(active));
        when(tenants.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(active, pending));

        assertThat(service.list(TenantStatus.ACTIVE)).hasSize(1);
        assertThat(service.list(null)).hasSize(2);
    }

    @Test
    void getUnknownTenantIs404() {
        assertThatThrownBy(() -> service.get(UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
    }
}
