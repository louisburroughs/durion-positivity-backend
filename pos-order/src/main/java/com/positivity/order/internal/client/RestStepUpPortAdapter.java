package com.positivity.order.internal.client;

import com.positivity.order.internal.exception.CashMovementRefusedException;
import com.positivity.order.internal.exception.StepUpUnavailableException;
import com.positivity.order.internal.security.OrderPermissions;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.tenancy.TenantContext;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * REST adapter on pos-security-service's internal step-up check (CAP:550 S16, #2512; AW31).
 *
 * <p>pos-security-service is an ADR-0044 utility module, so this synchronous call needs no domain-wall
 * exception. The path is under {@code /internal/}, which the gateway refuses at the edge; the call goes
 * through the load balancer inside the mesh. It carries the caller's tenant ({@code X-Tenant-Id}) — the
 * credentials are checked in that tenant only, never in one named by a request body (ADR-0062) — and
 * the cashier's identity with the authority this module already verified.
 *
 * <p>Every 4xx answer is a refusal ({@code CASH_MOVEMENT_APPROVAL_DENIED}, one code for every reason);
 * anything else means the check could not be made. The password is sent once and never logged.
 */
@Component
@Slf4j
public class RestStepUpPortAdapter implements StepUpPort {

    static final String PATH = "/internal/v1/auth/step-up";

    private final RestClient securityServiceRestClient;

    public RestStepUpPortAdapter(@Qualifier("securityServiceRestClient") RestClient securityServiceRestClient) {
        this.securityServiceRestClient = securityServiceRestClient;
    }

    @Override
    public @NonNull StepUpResult verify(
            @NonNull String username, @NonNull String password, @NonNull String permission) {
        UUID tenantId = TenantContext.require();
        String caller = SecurityContextHelper.getCurrentUsernameOrDefault("pos-order");
        StepUpResponse response;
        try {
            response = securityServiceRestClient
                    .post()
                    .uri(PATH)
                    .header(GatewaySecurityConstants.HEADER_USER, caller)
                    .header(GatewaySecurityConstants.HEADER_AUTHORITIES, OrderPermissions.ORDER_SESSION_CASH_MOVEMENT)
                    .header(GatewaySecurityConstants.HEADER_TENANT_ID, tenantId.toString())
                    .body(Map.of("username", username, "password", password, "permission", permission))
                    .retrieve()
                    .body(StepUpResponse.class);
        } catch (HttpClientErrorException e) {
            log.info("Step-up refused by pos-security-service status={} caller={}", e.getStatusCode(), caller);
            throw new CashMovementRefusedException(
                    CashMovementRefusedException.Refusal.APPROVAL_DENIED,
                    "The manager's credentials could not be verified for this approval");
        } catch (RestClientException e) {
            throw new StepUpUnavailableException("The manager's credentials could not be checked right now", e);
        }
        if (response == null || response.userId() == null) {
            throw new StepUpUnavailableException("pos-security-service returned an empty step-up answer", null);
        }
        return new StepUpResult(response.userId(), response.holdsPermission());
    }

    /** pos-security-service's step-up answer. */
    record StepUpResponse(UUID userId, boolean holdsPermission) {}
}
