package com.positivity.tenant.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

class TenantEventsListenerTest {

    private static final UUID TENANT = UUID.fromString("01990000-0000-7000-8000-000000000123");

    private final TenantProvisioningHandler handler = mock(TenantProvisioningHandler.class);
    private final TenantEventsListener listener = new TenantEventsListener(new ObjectMapper(), handler);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static String event(String type, String tenantId) {
        return """
                {"eventId":"01990000-0000-7000-8000-0000000000ee","eventType":"%s","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":1,"occurredAtUtc":"2026-09-10T12:00:00Z",
                 "sourceService":"pos-security-service","payload":{"tenantId":"%s"}}
                """.formatted(type, tenantId, tenantId);
    }

    @Test
    @DisplayName("tenant.provisioned is applied under the platform tenant, whatever the record was bound to")
    void provisionedRunsAsPlatform() {
        AtomicReference<UUID> boundDuringHandling = new AtomicReference<>();
        doAnswer(inv -> {
                    boundDuringHandling.set(TenantContext.require());
                    return null;
                })
                .when(handler)
                .apply(any(), any());
        TenantContext.bind(TENANT); // what TenantRecordInterceptor bound from the header

        listener.onEvent(event("tenant.provisioned", TENANT.toString()));

        verify(handler).apply(eq("01990000-0000-7000-8000-0000000000ee"), eq(TENANT));
        assertThat(boundDuringHandling.get()).isEqualTo(PlatformTenant.ID);
        assertThat(TenantContext.current())
                .as("the interceptor's binding is restored")
                .contains(TENANT);
    }

    @Test
    void ownFactsAndGarbageAreIgnored() {
        listener.onEvent(event("tenant.created", TENANT.toString()));
        listener.onEvent(event("tenant.provisioned", "not-a-uuid"));
        listener.onEvent("{\"eventType\":\"tenant.provisioned\"}");
        listener.onEvent("not json");
        verifyNoInteractions(handler);
    }

    @Test
    @DisplayName("a transient database failure propagates so the container retries and dead-letters")
    void transientFailurePropagates() {
        doThrow(new QueryTimeoutException("slow")).when(handler).apply(any(), any());
        assertThatThrownBy(() -> listener.onEvent(event("tenant.provisioned", TENANT.toString())))
                .isInstanceOf(QueryTimeoutException.class);
    }
}
