package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.CannotAcquireLockException;
import tools.jackson.databind.ObjectMapper;

/** The {@code tenant.created} consumer (#2526): what it acts on, what it skips, and in which binding. */
@DisplayName("TenantEventsListener")
class TenantEventsListenerTest {

    private static final UUID NEW_TENANT = UUID.fromString("01990000-0000-7000-8000-0000000000b2");
    private static final String EVENT_ID = "01990000-0000-7000-8000-0000000000e1";
    private static final AccountingTemplate SNAPSHOT = AccountingTemplate.of(
            List.of(new AccountingTemplate.Category("INVOICE_REVENUE", "Invoice revenue recognition")));

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final AccountingTemplateReader reader = mock(AccountingTemplateReader.class);
    private final AccountingTenantProvisioner provisioner = mock(AccountingTenantProvisioner.class);
    private final List<Optional<UUID>> boundAtRead = new ArrayList<>();
    private final List<Optional<UUID>> boundAtProvision = new ArrayList<>();

    private TenantEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new TenantEventsListener(new ObjectMapper(), processedEvents, reader, provisioner);
        when(reader.snapshot()).thenAnswer(invocation -> {
            boundAtRead.add(TenantContext.current());
            return SNAPSHOT;
        });
        when(provisioner.provision(any(), any(), any())).thenAnswer(invocation -> {
            boundAtProvision.add(TenantContext.current());
            return Optional.empty();
        });
        // The record interceptor binds the tenant of the message header: pos-tenant's rows are platform rows.
        TenantContext.bind(PlatformTenant.ID);
    }

    @AfterEach
    void clearBinding() {
        TenantContext.clear();
    }

    @Test
    @DisplayName(
            "tenant.created: the template is read before the new tenant is bound, then provisioning runs bound to it")
    void provisionsTheNewTenantUnderItsOwnBinding() {
        listener.onTenantEvent(created(EVENT_ID, NEW_TENANT.toString()));

        InOrder order = inOrder(reader, provisioner);
        order.verify(reader).snapshot();
        order.verify(provisioner).provision(NEW_TENANT, EVENT_ID, SNAPSHOT);
        assertThat(boundAtRead).containsExactly(Optional.of(PlatformTenant.ID));
        assertThat(boundAtProvision).containsExactly(Optional.of(NEW_TENANT));
        assertThat(TenantContext.current()).contains(PlatformTenant.ID);
    }

    @Test
    @DisplayName("every other tenant fact is ignored without a look-up")
    void ignoresOtherEventTypes() {
        listener.onTenantEvent("""
                {"eventId":"01990000-0000-7000-8000-0000000000e2","eventType":"tenant.suspended","aggregateVersion":2,
                 "payload":{"tenantId":"%s","slug":"acme","status":"SUSPENDED"}}
                """.formatted(NEW_TENANT));
        listener.onTenantEvent("""
                {"eventId":"01990000-0000-7000-8000-0000000000e3","eventType":"tenant.provisioned","aggregateVersion":2,
                 "payload":{"tenantId":"%s"}}
                """.formatted(NEW_TENANT));

        verifyNoInteractions(processedEvents, reader, provisioner);
    }

    @Test
    @DisplayName("a known eventId is skipped before anything is read")
    void skipsAKnownEventId() {
        when(processedEvents.existsById(EVENT_ID)).thenReturn(true);

        listener.onTenantEvent(created(EVENT_ID, NEW_TENANT.toString()));

        verifyNoInteractions(reader, provisioner);
    }

    @Test
    @DisplayName("tenant.created for the platform tenant is recorded and nothing is provisioned")
    void recordsAndSkipsThePlatformTenant() {
        listener.onTenantEvent(created(EVENT_ID, PlatformTenant.ID.toString()));

        verify(provisioner).recordSkipped(EVENT_ID);
        verify(provisioner, never()).provision(any(), any(), any());
        verifyNoInteractions(reader);
    }

    @Test
    @DisplayName("an unparsable record, one without an eventId and a malformed payload are dropped")
    void dropsMalformedRecords() {
        listener.onTenantEvent("{not json");
        listener.onTenantEvent("""
                {"eventType":"tenant.created","aggregateVersion":1,
                 "payload":{"tenantId":"%s","slug":"acme","status":"PENDING"}}
                """.formatted(NEW_TENANT));
        listener.onTenantEvent(created(EVENT_ID, "not-a-uuid"));

        verifyNoInteractions(reader, provisioner);
    }

    @Test
    @DisplayName(
            "an empty template propagates, so the record retries and dead-letters; nothing is provisioned or recorded")
    void emptyTemplatePropagates() {
        when(reader.snapshot()).thenThrow(new EmptyAccountingTemplateException());

        assertThatThrownBy(() -> listener.onTenantEvent(created(EVENT_ID, NEW_TENANT.toString())))
                .isInstanceOf(EmptyAccountingTemplateException.class);

        verifyNoInteractions(provisioner);
    }

    @Test
    @DisplayName("a provisioning failure propagates for container retry and the caller's binding is restored")
    void provisioningFailurePropagates() {
        when(provisioner.provision(any(), any(), any())).thenThrow(new CannotAcquireLockException("deadlock"));

        assertThatThrownBy(() -> listener.onTenantEvent(created(EVENT_ID, NEW_TENANT.toString())))
                .isInstanceOf(CannotAcquireLockException.class);

        assertThat(TenantContext.current()).contains(PlatformTenant.ID);
    }

    private static String created(String eventId, String tenantId) {
        return """
                {"eventId":"%s","eventType":"tenant.created","aggregateVersion":1,
                 "payload":{"tenantId":"%s","slug":"acme","displayName":"Acme","status":"PENDING",
                            "initialAdminEmail":"owner@acme.example"}}
                """.formatted(eventId, tenantId);
    }
}
