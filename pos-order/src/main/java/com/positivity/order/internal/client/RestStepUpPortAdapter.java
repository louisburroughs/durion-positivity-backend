package com.positivity.order.internal.client;

import com.positivity.order.internal.exception.CashMovementRefusedException;
import com.positivity.order.internal.exception.StepUpUnavailableException;
import com.positivity.order.internal.security.OrderPermissions;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.SecurityApiConstants;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.tenancy.TenantContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * REST adapter on pos-security-service's internal step-up check (CAP:550 S16, #2512; AW31).
 *
 * <p>pos-security-service is an ADR-0044 utility module, so this synchronous call needs no domain-wall
 * exception. The path is under {@code /internal/}, which the gateway refuses at the edge; the call goes
 * through the load balancer inside the mesh and authenticates with the mesh service credential ({@code
 * pos.security.api-secret}, sent as {@value SecurityApiConstants#INTERNAL_SECRET_HEADER}). It carries the
 * caller's tenant ({@code X-Tenant-Id}) — the credentials are checked in that tenant only, never in one
 * named by a request body (ADR-0062) — and relays the cashier's name for the audit log.
 *
 * <p>Only a 403 whose body code is {@code STEP_UP_DENIED} is a refusal ({@code
 * CASH_MOVEMENT_APPROVAL_DENIED}, one code for every reason). Any other answer — another status, another
 * code, a transport failure, an empty body — means the check could not be made, logged at error level and
 * answered {@code CASH_MOVEMENT_APPROVAL_UNAVAILABLE}, so a misconfigured mesh never reads as a wrong
 * password. The password is sent once and never logged.
 */
@Component
@Slf4j
public class RestStepUpPortAdapter implements StepUpPort {

    static final String PATH = "/internal/v1/auth/step-up";
    static final String DENIED_CODE = "STEP_UP_DENIED";

    private final RestClient securityServiceRestClient;
    private final ObjectMapper objectMapper;
    private final String serviceSecret;

    public RestStepUpPortAdapter(
            @Qualifier("securityServiceRestClient") RestClient securityServiceRestClient,
            ObjectMapper objectMapper,
            @Value("${" + SecurityApiConstants.PERMISSION_SECRET_PROPERTY + ":}") String serviceSecret) {
        this.securityServiceRestClient = securityServiceRestClient;
        this.objectMapper = objectMapper;
        this.serviceSecret = serviceSecret;
    }

    @Override
    public @NonNull StepUpResult verify(
            @NonNull String username, @NonNull String password, @NonNull String permission, @Nullable UUID locationId) {
        if (!SecurityApiConstants.hasSecret(serviceSecret)) {
            log.error(
                    "Step-up cannot be called: {} is not configured", SecurityApiConstants.PERMISSION_SECRET_PROPERTY);
            throw new StepUpUnavailableException("The manager's credentials cannot be checked: not configured", null);
        }
        UUID tenantId = TenantContext.require();
        String caller = SecurityContextHelper.getCurrentUsernameOrDefault("pos-order");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        body.put("permission", permission);
        body.put("locationId", locationId);
        StepUpResponse response;
        try {
            response = securityServiceRestClient
                    .post()
                    .uri(PATH)
                    .header(SecurityApiConstants.INTERNAL_SECRET_HEADER, serviceSecret)
                    .header(GatewaySecurityConstants.HEADER_USER, caller)
                    .header(GatewaySecurityConstants.HEADER_AUTHORITIES, OrderPermissions.ORDER_SESSION_CASH_MOVEMENT)
                    .header(GatewaySecurityConstants.HEADER_TENANT_ID, tenantId.toString())
                    .body(body)
                    .retrieve()
                    .body(StepUpResponse.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.FORBIDDEN) && DENIED_CODE.equals(codeOf(e))) {
                log.info("Step-up refused by pos-security-service caller={}", caller);
                throw new CashMovementRefusedException(
                        CashMovementRefusedException.Refusal.APPROVAL_DENIED,
                        "The manager's credentials could not be verified for this approval");
            }
            log.error(
                    "Step-up answered {} code={} caller={}: treated as unavailable, not as a refusal",
                    e.getStatusCode(),
                    codeOf(e),
                    caller);
            throw new StepUpUnavailableException("The manager's credentials could not be checked right now", e);
        } catch (RestClientException e) {
            log.error("Step-up call to pos-security-service failed caller={}", caller, e);
            throw new StepUpUnavailableException("The manager's credentials could not be checked right now", e);
        }
        if (response == null || response.userId() == null) {
            log.error("Step-up answered an empty body caller={}", caller);
            throw new StepUpUnavailableException("pos-security-service returned an empty step-up answer", null);
        }
        return new StepUpResult(
                response.userId(),
                response.holdsPermission(),
                response.financialScoped(),
                response.otherScoped(),
                response.assignedLocationIds() == null ? List.of() : response.assignedLocationIds());
    }

    private @Nullable String codeOf(RestClientResponseException e) {
        try {
            JsonNode body = objectMapper.readTree(e.getResponseBodyAsString());
            return body == null ? null : body.path("code").stringValue(null);
        } catch (RuntimeException _) {
            return null;
        }
    }

    /** pos-security-service's step-up answer. */
    record StepUpResponse(
            UUID userId,
            boolean holdsPermission,
            boolean financialScoped,
            boolean otherScoped,
            List<UUID> assignedLocationIds) {}
}
