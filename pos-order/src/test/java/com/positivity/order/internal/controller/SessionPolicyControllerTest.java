package com.positivity.order.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.order.BaseControllerSliceTest;
import com.positivity.order.internal.exception.SessionPolicyConflictException;
import com.positivity.order.internal.exception.SessionPolicyValidationException;
import com.positivity.order.internal.service.SessionPolicyService;
import com.positivity.order.internal.service.model.SessionPolicyChangeView;
import com.positivity.order.internal.service.model.SessionPolicyView;
import com.positivity.order.internal.service.model.UpdateSessionPolicyCommand;
import com.positivity.security.common.GatewaySecurityConfig;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** CAP:550 S16 (#2512; §7.2): the drawer-policy endpoints, their permission and their codes. */
@DisplayName("Drawer policy endpoints (CAP:550 S16)")
@WebMvcTest(SessionPolicyController.class)
@Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class, BaseControllerSliceTest.SliceConfig.class})
class SessionPolicyControllerTest extends BaseControllerSliceTest {

    private static final String MANAGE = "order:session_policy:manage";
    private static final String BODY = """
            {"version":2,"currencyCode":"USD",
             "pettyExpense":{"allowed":true,"cashierLimit":50.00},
             "vendorCod":{"allowed":false,"cashierLimit":null},
             "overShortTolerance":3.00,
             "justification":"Tighter count after the audit"}
            """;

    @MockitoBean
    private SessionPolicyService sessionPolicyService;

    private static SessionPolicyView defaults() {
        return new SessionPolicyView(
                null, true, new BigDecimal("50.0000"), false, null, new BigDecimal("5.0000"), "USD");
    }

    @Test
    @DisplayName("AC5: GET returns four rows — petty on at 50.00, COD off, bank drop and float read-only — and 5.00")
    void getReturnsPolicy() throws Exception {
        when(sessionPolicyService.current()).thenReturn(defaults());
        when(sessionPolicyService.history()).thenReturn(List.of());

        mockMvc.perform(withGatewayAuth(get("/v1/orders/session-policy"), MANAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.types.length()").value(4))
                .andExpect(jsonPath("$.types[0].type").value("PETTY_EXPENSE"))
                .andExpect(jsonPath("$.types[0].allowed").value(true))
                .andExpect(jsonPath("$.types[0].cashierLimit").value(50.0))
                .andExpect(jsonPath("$.types[0].editable").value(true))
                .andExpect(jsonPath("$.types[1].type").value("VENDOR_COD"))
                .andExpect(jsonPath("$.types[1].allowed").value(false))
                .andExpect(jsonPath("$.types[2].type").value("BANK_DROP"))
                .andExpect(jsonPath("$.types[2].editable").value(false))
                .andExpect(jsonPath("$.types[3].type").value("FLOAT_CHANGE"))
                .andExpect(jsonPath("$.types[3].alwaysNeedsManager").value(true))
                .andExpect(jsonPath("$.types[3].editable").value(false))
                .andExpect(jsonPath("$.overShortTolerance").value(5.0))
                .andExpect(jsonPath("$.currencyCode").value("USD"));
    }

    @Test
    @DisplayName("#2608: GET serves each history row's actorName beside its actor, null when unresolved")
    void getServesActorNames() throws Exception {
        when(sessionPolicyService.current()).thenReturn(defaults());
        when(sessionPolicyService.history())
                .thenReturn(List.of(
                        new SessionPolicyChangeView(
                                "OVER_SHORT_TOLERANCE",
                                "5.00",
                                "3.00",
                                "dana.reyes",
                                "Tighter count after the audit",
                                Instant.parse("2026-10-07T12:00:00Z"),
                                "Dana Reyes"),
                        new SessionPolicyChangeView(
                                "PETTY_EXPENSE_LIMIT",
                                "40.00",
                                "50.00",
                                "unlinked.user",
                                "Raised for the supplier run",
                                Instant.parse("2026-10-06T12:00:00Z"),
                                null)));

        mockMvc.perform(withGatewayAuth(get("/v1/orders/session-policy"), MANAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history[*].actorName").exists())
                .andExpect(jsonPath("$.history[0].actorName").value("Dana Reyes"))
                .andExpect(jsonPath("$.history[0].actor").value("dana.reyes"))
                .andExpect(jsonPath("$.history[1].actorName").doesNotExist())
                .andExpect(jsonPath("$.history[1].actor").value("unlinked.user"));
    }

    @Test
    @DisplayName("PUT passes the two configurable types, the tolerance and the justification; returns the history")
    void putUpdates() throws Exception {
        when(sessionPolicyService.update(any()))
                .thenReturn(new SessionPolicyView(
                        3L, true, new BigDecimal("50.0000"), false, null, new BigDecimal("3.0000"), "USD"));
        when(sessionPolicyService.history())
                .thenReturn(List.of(new SessionPolicyChangeView(
                        "OVER_SHORT_TOLERANCE",
                        "5.00",
                        "3.00",
                        "controller-1",
                        "Tighter count after the audit",
                        Instant.parse("2026-10-07T12:00:00Z"),
                        "Dana Reyes")));

        mockMvc.perform(withGatewayAuth(
                        put("/v1/orders/session-policy")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(BODY),
                        MANAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overShortTolerance").value(3.0))
                .andExpect(jsonPath("$.history[0].oldValue").value("5.00"))
                .andExpect(jsonPath("$.history[0].newValue").value("3.00"))
                // #2608: the actor's display name beside the username, which stays served for audit.
                .andExpect(jsonPath("$.history[0].actorName").value("Dana Reyes"))
                .andExpect(jsonPath("$.history[0].actor").value("controller-1"));

        ArgumentCaptor<UpdateSessionPolicyCommand> command = ArgumentCaptor.forClass(UpdateSessionPolicyCommand.class);
        verify(sessionPolicyService).update(command.capture());
        assertThat(command.getValue().overShortTolerance()).isEqualByComparingTo("3.00");
        assertThat(command.getValue().pettyExpenseAllowed()).isTrue();
        assertThat(command.getValue().vendorCodAllowed()).isFalse();
        assertThat(command.getValue().expectedVersion()).isEqualTo(2L);
        assertThat(command.getValue().currencyCode()).isEqualTo("USD");
    }

    @Test
    @DisplayName("AC12: a caller without order:session_policy:manage gets 403 on PUT and GET")
    void manageRequired() throws Exception {
        mockMvc.perform(withGatewayAuth(
                        put("/v1/orders/session-policy")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(BODY),
                        "order:session:cash_movement"))
                .andExpect(status().isForbidden());
        mockMvc.perform(withGatewayAuth(get("/v1/orders/session-policy"), "order:session:view"))
                .andExpect(status().isForbidden());
        verify(sessionPolicyService, never()).update(any());
    }

    @Test
    @DisplayName("#2651: a sub-minor-unit limit answers 422 AMOUNT_PRECISION_EXCEEDS_CURRENCY naming the field")
    void precisionRefusal() throws Exception {
        when(sessionPolicyService.update(any()))
                .thenThrow(new com.positivity.order.internal.exception.CashMovementTaxRefusedException(
                        com.positivity.order.internal.exception.CashMovementTaxRefusedException.Code
                                .AMOUNT_PRECISION_EXCEEDS_CURRENCY,
                        "An amount has more decimals than USD allows; nothing is rounded",
                        java.util.List.of(new com.positivity.shared.error.ApiError.FieldError(
                                "pettyExpense.cashierLimit", "has more decimals than USD allows"))));

        mockMvc.perform(withGatewayAuth(
                        put("/v1/orders/session-policy")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(BODY),
                        MANAGE))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AMOUNT_PRECISION_EXCEEDS_CURRENCY"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("pettyExpense.cashierLimit"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("AC6: a field rule answers 400 VALIDATION_ERROR; a lost race 409 SESSION_POLICY_CONFLICT")
    void validationAndConflict() throws Exception {
        when(sessionPolicyService.update(any()))
                .thenThrow(new SessionPolicyValidationException(
                        "justification", "justification must be at least 10 characters"))
                .thenThrow(new SessionPolicyConflictException("changed by someone else", null));

        mockMvc.perform(withGatewayAuth(
                        put("/v1/orders/session-policy")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(BODY),
                        MANAGE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("justification"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("justification must be at least 10 characters"));
        mockMvc.perform(withGatewayAuth(
                        put("/v1/orders/session-policy")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(BODY),
                        MANAGE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_POLICY_CONFLICT"));
    }

    @Test
    @DisplayName("ADR-0067: another currency is 422 CURRENCY_NOT_SUPPORTED; a non-ISO code is 400 before the service")
    void currencyAnswers() throws Exception {
        when(sessionPolicyService.update(any()))
                .thenThrow(new com.positivity.order.internal.exception.CurrencyNotSupportedException("CAD"));

        mockMvc.perform(withGatewayAuth(
                        put("/v1/orders/session-policy")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(BODY.replace("\"USD\"", "\"CAD\"")),
                        MANAGE))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_NOT_SUPPORTED"));
        org.mockito.Mockito.clearInvocations(sessionPolicyService);
        mockMvc.perform(withGatewayAuth(
                        put("/v1/orders/session-policy")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(BODY.replace("\"USD\"", "\"DOLLARS\"")),
                        MANAGE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                // #2674: the bean-validation refusal names the field too, through the platform handler.
                .andExpect(jsonPath("$.fieldErrors[0].field").value("currencyCode"));
        verify(sessionPolicyService, never()).update(any());
    }

    /**
     * #2674: the handler puts the service's field on the envelope, for every path the page maps. The rules that
     * choose the field are proven in SessionPolicyServiceImplTest; the currencyCode row is the null/blank case, a
     * non-ISO code is refused before the service (below).
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "justification, justification must be at least 10 characters",
        "pettyExpense.allowed, pettyExpense.allowed is required",
        "vendorCod.allowed, vendor cash on delivery cannot be switched on until pos-order holds the vendor list",
        "overShortTolerance, overShortTolerance must not be negative",
        "currencyCode, currencyCode is required and must be an ISO 4217 code",
        "pettyExpense.cashierLimit, pettyExpense.cashierLimit is required while petty expenses are allowed",
        "vendorCod.cashierLimit, vendorCod.cashierLimit is required to switch vendor cash on delivery on"
    })
    @DisplayName("#2674: a field rule answers 400 VALIDATION_ERROR with fieldErrors naming the field")
    void fieldRuleNamesItsField(String field, String message) throws Exception {
        when(sessionPolicyService.update(any())).thenThrow(new SessionPolicyValidationException(field, message));

        mockMvc.perform(withGatewayAuth(
                        put("/v1/orders/session-policy")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(BODY),
                        MANAGE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value(field))
                .andExpect(jsonPath("$.fieldErrors[0].message").value(message));
    }
}
