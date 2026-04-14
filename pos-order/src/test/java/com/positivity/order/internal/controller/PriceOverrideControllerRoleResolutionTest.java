package com.positivity.order.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.dto.ApprovePriceOverrideRequest;
import com.positivity.order.internal.dto.RejectPriceOverrideRequest;
import com.positivity.order.internal.security.PriceOverridePermissions;
import com.positivity.order.service.PriceOverrideService;
import com.positivity.order.service.model.PriceOverrideDetail;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class PriceOverrideControllerRoleResolutionTest {

    private final PriceOverrideService priceOverrideService = mock(PriceOverrideService.class);
    private final PriceOverrideController controller = new PriceOverrideController(priceOverrideService);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void approvePriceOverride_usesRoleFromAuthorities() {
        UUID overrideId = UUID.randomUUID();
        ApprovePriceOverrideRequest request = new ApprovePriceOverrideRequest();
        request.setComments("approved");

        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        "reviewer",
                        "n/a",
                        List.of(
                                new SimpleGrantedAuthority(PriceOverridePermissions.PRICE_OVERRIDE_APPROVE),
                                new SimpleGrantedAuthority("ROLE_MANAGER"))));

        when(priceOverrideService.approveOverride(any(), any())).thenReturn(sampleDetail("APPROVED"));

        ResponseEntity<PriceOverrideDetail> response = controller.approvePriceOverride(overrideId, request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(priceOverrideService)
                .approveOverride(
                        any(),
                        argThat(command ->
                                "MANAGER".equals(command.approverRole()) && "approved".equals(command.comments())));
    }

    @Test
    void rejectPriceOverride_usesUnknownWhenNoRoleAuthority() {
        UUID overrideId = UUID.randomUUID();
        RejectPriceOverrideRequest request = new RejectPriceOverrideRequest();
        request.setReason("policy");
        request.setComments("no role");

        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        "reviewer",
                        "n/a",
                        List.of(new SimpleGrantedAuthority(PriceOverridePermissions.PRICE_OVERRIDE_REJECT))));

        when(priceOverrideService.rejectOverride(any(), any())).thenReturn(sampleDetail("REJECTED"));

        ResponseEntity<PriceOverrideDetail> response = controller.rejectPriceOverride(overrideId, request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(priceOverrideService)
                .rejectOverride(
                        any(),
                        argThat(command -> "UNKNOWN".equals(command.reviewerRole())
                                && "policy".equals(command.reason())
                                && "no role".equals(command.comments())));
    }

    private PriceOverrideDetail sampleDetail(String status) {
        UUID id = UUID.randomUUID();
        String idString = id.toString();
        return new PriceOverrideDetail(
                id,
                idString,
                idString,
                idString,
                BigDecimal.TEN,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                "PRICE_MATCH",
                "test",
                status,
                false,
                false,
                idString,
                idString,
                null,
                null,
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null);
    }
}
