package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.config.OrderDomainEventPublisher;
import com.positivity.order.internal.entity.RegisterSession;
import com.positivity.order.internal.entity.RegisterSessionStatus;
import com.positivity.order.internal.repository.RegisterSessionRepository;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantIterator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * CAP:550 S40 (#2578) AC3: at start, pos-order re-emits {@code order.session.opened} for every OPEN or CLOSING
 * session, per tenant, at the session's current version; CLOSED sessions are left out, and {@code
 * pos.order.session.bootstrap-republish.enabled=false} turns the republish off.
 */
@DisplayName("RegisterSessionFactsBootstrap — order.session.opened republish (CAP:550 S40)")
class RegisterSessionFactsBootstrapTest {

    private static final UUID TENANT_A = UUID.fromString("01900000-0000-7000-8000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("01900000-0000-7000-8000-00000000000b");

    private final RegisterSessionRepository sessions = mock(RegisterSessionRepository.class);
    private final OrderDomainEventPublisher publisher = mock(OrderDomainEventPublisher.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    private RegisterSessionFactsBootstrap bootstrap(List<UUID> tenants) {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(inv -> new SimpleTransactionStatus());
        return new RegisterSessionFactsBootstrap(
                new TenantIterator(() -> tenants), sessions, publisher, transactionManager);
    }

    @Test
    @DisplayName("republishes one fact per OPEN or CLOSING session of the bound tenant, and asks for no other status")
    void republishesActiveSessionsOnly() {
        RegisterSession open = session("T-1", RegisterSessionStatus.OPEN, 0L);
        RegisterSession closing = session("T-2", RegisterSessionStatus.CLOSING, 1L);
        when(sessions.findByStatusInOrderByOpenedAtAscSessionIdAsc(any())).thenReturn(List.of(open, closing));

        assertThat(bootstrap(List.of()).republishBoundTenant()).isEqualTo(2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<RegisterSessionStatus>> statuses =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(sessions).findByStatusInOrderByOpenedAtAscSessionIdAsc(statuses.capture());
        // CLOSED sessions are never asked for, so none is republished.
        assertThat(statuses.getValue())
                .containsExactlyInAnyOrder(RegisterSessionStatus.OPEN, RegisterSessionStatus.CLOSING);
        ArgumentCaptor<RegisterSession> published = ArgumentCaptor.forClass(RegisterSession.class);
        verify(publisher, org.mockito.Mockito.times(2)).publishRegisterSessionOpened(published.capture());
        // The live session is handed over as it stands, so the fact carries its current version.
        assertThat(published.getAllValues()).containsExactly(open, closing);
    }

    @Test
    @DisplayName("visits every tenant with that tenant bound, each in its own transaction")
    void republishesPerTenant() {
        Map<UUID, List<RegisterSession>> byTenant = Map.of(
                TENANT_A,
                List.of(
                        session("A-1", RegisterSessionStatus.OPEN, 0L),
                        session("A-2", RegisterSessionStatus.CLOSING, 1L)),
                TENANT_B,
                List.of(session("B-1", RegisterSessionStatus.OPEN, 0L)));
        List<UUID> boundWhileReading = new ArrayList<>();
        when(sessions.findByStatusInOrderByOpenedAtAscSessionIdAsc(any())).thenAnswer(inv -> {
            UUID bound = TenantContext.current().orElseThrow();
            boundWhileReading.add(bound);
            return byTenant.get(bound);
        });

        int queued = bootstrap(List.of(TENANT_A, TENANT_B)).republishAll();

        assertThat(queued).isEqualTo(3);
        assertThat(boundWhileReading).containsExactly(TENANT_A, TENANT_B);
        verify(transactionManager, org.mockito.Mockito.times(2)).getTransaction(any(TransactionDefinition.class));
        verify(transactionManager, org.mockito.Mockito.times(2)).commit(any());
    }

    @Test
    @DisplayName("a failing republish is logged and never fails the start")
    void failureNeverFailsTheStart() {
        when(sessions.findByStatusInOrderByOpenedAtAscSessionIdAsc(any())).thenThrow(new IllegalStateException("db"));
        RegisterSessionFactsBootstrap bootstrap = bootstrap(List.of(TENANT_A));

        bootstrap.run(mock(ApplicationArguments.class));

        verify(publisher, never()).publishRegisterSessionOpened(any());
    }

    @Test
    @DisplayName("is on by default and pos.order.session.bootstrap-republish.enabled=false turns it off")
    void flagControlsTheBean() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(TenantIterator.class, () -> new TenantIterator(List::of))
                .withBean(RegisterSessionRepository.class, () -> sessions)
                .withBean(OrderDomainEventPublisher.class, () -> publisher)
                .withBean(PlatformTransactionManager.class, () -> transactionManager)
                .withUserConfiguration(RegisterSessionFactsBootstrap.class);

        runner.run(context -> assertThat(context).hasSingleBean(RegisterSessionFactsBootstrap.class));
        runner.withPropertyValues("pos.order.session.bootstrap-republish.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(RegisterSessionFactsBootstrap.class));
        runner.withPropertyValues("pos.order.session.bootstrap-republish.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RegisterSessionFactsBootstrap.class));
        verifyNoInteractions(publisher);
    }

    private static RegisterSession session(String terminalId, RegisterSessionStatus status, long version) {
        return RegisterSession.builder()
                .sessionId(UUID.randomUUID())
                .version(version)
                .terminalId(terminalId)
                .locationId(UUID.randomUUID())
                .openedByClerkId("clerk-1")
                .status(status)
                .currencyCode("USD")
                .openingFloat(new BigDecimal("100.0000"))
                .openedAt(Instant.parse("2026-10-07T08:00:00Z"))
                .build();
    }
}
