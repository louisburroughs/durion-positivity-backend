package com.positivity.order.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.positivity.order.internal.exception.CashMovementRefusedException;
import com.positivity.order.internal.exception.StepUpUnavailableException;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP:550 S16 (#2512, review B1/M1/M2): the step-up call carries the mesh service credential, the
 * caller's tenant and the cashier's name, and only a 403 {@code STEP_UP_DENIED} is a refusal — every
 * other answer is "unavailable", never a wrong password.
 */
@DisplayName("RestStepUpPortAdapter — step-up call and status mapping")
class RestStepUpPortAdapterTest {

    private static final UUID TENANT = UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID LOCATION = UUID.fromString("01900000-0000-7000-8000-00000000d001");
    private static final UUID MANAGER = UUID.fromString("01900000-0000-7000-8000-00000000b001");
    private static final String URL = "http://security-service/internal/v1/auth/step-up";
    private static final String PERMISSION = "order:session:approve_cash_movement";

    private MockRestServiceServer server;
    private RestStepUpPortAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new RestStepUpPortAdapter(
                builder.baseUrl("http://security-service").build(), new ObjectMapper(), "mesh-secret");
        TenantContext.bind(TENANT);
        var token = new UsernamePasswordAuthenticationToken("cashier", "n/a", List.of());
        token.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "cashier"));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("200: sends the credential, tenant, cashier and location, and maps the scope answer")
    void verifiedAnswer() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Api-Secret", "mesh-secret"))
                .andExpect(header("X-Tenant-Id", TENANT.toString()))
                .andExpect(header("X-User", "cashier"))
                .andExpect(content().json("""
                        {"username":"manager","password":"s3cret","permission":"%s","locationId":"%s"}
                        """.formatted(PERMISSION, LOCATION)))
                .andRespond(withSuccess("""
                        {"userId":"%s","holdsPermission":true,"financialScoped":false,"otherScoped":true,
                         "assignedLocationIds":["%s"]}
                        """.formatted(MANAGER, LOCATION), MediaType.APPLICATION_JSON));

        StepUpPort.StepUpResult result = adapter.verify("manager", "s3cret", PERMISSION, LOCATION);

        assertThat(result.userId()).isEqualTo(MANAGER);
        assertThat(result.holdsPermission()).isTrue();
        assertThat(result.otherScoped()).isTrue();
        assertThat(result.assignedLocationIds()).containsExactly(LOCATION);
        server.verify();
    }

    @Test
    @DisplayName("403 STEP_UP_DENIED is the one refusal: CASH_MOVEMENT_APPROVAL_DENIED")
    void deniedIsARefusal() {
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"STEP_UP_DENIED\",\"message\":\"The credentials could not be verified\"}"));

        assertThatThrownBy(() -> adapter.verify("manager", "wrong", PERMISSION, LOCATION))
                .isInstanceOf(CashMovementRefusedException.class)
                .satisfies(e -> assertThat(((CashMovementRefusedException) e).refusal())
                        .isEqualTo(CashMovementRefusedException.Refusal.APPROVAL_DENIED));
    }

    @Test
    @DisplayName("M1: any other 403, a 401 (wrong mesh credential), a 400 or a 5xx is unavailable, not a refusal")
    void everythingElseIsUnavailable() {
        for (var status : List.of(
                HttpStatus.FORBIDDEN,
                HttpStatus.UNAUTHORIZED,
                HttpStatus.BAD_REQUEST,
                HttpStatus.SERVICE_UNAVAILABLE)) {
            server.reset();
            server.expect(requestTo(URL))
                    .andRespond(withStatus(status)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"code\":\"INVALID_INTERNAL_SECRET\"}"));

            assertThatThrownBy(() -> adapter.verify("manager", "s3cret", PERMISSION, LOCATION))
                    .as(status.toString())
                    .isInstanceOf(StepUpUnavailableException.class);
        }
    }

    @Test
    @DisplayName("an unconfigured mesh credential never calls out and is unavailable")
    void missingSecretIsUnavailable() {
        RestStepUpPortAdapter unconfigured = new RestStepUpPortAdapter(
                RestClient.builder().baseUrl("http://security-service").build(), new ObjectMapper(), "");

        assertThatThrownBy(() -> unconfigured.verify("manager", "s3cret", PERMISSION, LOCATION))
                .isInstanceOf(StepUpUnavailableException.class);
    }
}
