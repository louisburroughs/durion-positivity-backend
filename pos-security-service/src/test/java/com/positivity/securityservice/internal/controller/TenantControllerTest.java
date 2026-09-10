package com.positivity.securityservice.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.dto.TenantMeResponse;
import com.positivity.securityservice.internal.service.TenantQueryService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class TenantControllerTest {

    private static final UUID TENANT = UUID.fromString("01990000-0000-7000-8000-000000000123");

    private final TenantQueryService service = mock(TenantQueryService.class);
    private final TenantController controller = new TenantController(service);

    @Test
    void answersTheServiceView() {
        TenantMeResponse view = new TenantMeResponse(TENANT, "acme", "Acme", "ACTIVE");
        when(service.currentTenant()).thenReturn(Optional.of(view));

        assertThat(controller.me().getBody()).isSameAs(view);
    }

    @Test
    void replicaLagIs404() {
        when(service.currentTenant()).thenReturn(Optional.empty());

        assertThatThrownBy(controller::me)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
