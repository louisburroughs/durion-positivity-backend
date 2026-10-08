package com.positivity.accounting.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseControllerSliceTest;
import com.positivity.accounting.internal.dto.ApApprovalPolicyRequest;
import com.positivity.accounting.internal.dto.ApApprovalPolicyResponse;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.service.ApApprovalPolicyService;
import com.positivity.security.common.GatewaySecurityConfig;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The AP approval policy endpoints (CAP:550 S13, #2510; §5.5) through the production security chain and exception
 * handler: accounting:ap_approval_policy:manage on both, the validation envelope with fieldErrors, and an actor in the
 * body ignored (AC12, ADR-0018).
 */
@DisplayName("ApApprovalPolicyController: permission and envelopes (#2510)")
@WebMvcTest(ApApprovalPolicyController.class)
@Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class, BaseControllerSliceTest.SliceConfig.class})
class ApApprovalPolicyControllerTest extends BaseControllerSliceTest {

    private static final String BASE = "/v1/accounting/ap-approval-policy";
    private static final String MANAGE = "accounting:ap_approval_policy:manage";
    private static final UUID REQUEST = UUID.fromString("0199c0de-7a1b-7c2d-8e3f-4a5b6c7d8e9f");

    @MockitoBean
    private ApApprovalPolicyService policyService;

    private static ApApprovalPolicyResponse policy() {
        return new ApApprovalPolicyResponse(
                new BigDecimal("2500.00"),
                new BigDecimal("500.00"),
                "USD",
                false,
                false,
                "NET30",
                BaseControllerSliceTest.TEST_CLOCK.instant(),
                List.of(),
                0,
                20,
                0);
    }

    @Test
    @DisplayName("GET and PUT need accounting:ap_approval_policy:manage; a clerk's ap:approve is 403")
    void permission() throws Exception {
        when(policyService.get(anyInt(), anyInt())).thenReturn(policy());
        when(policyService.set(any())).thenReturn(policy());

        mockMvc.perform(withAuth(get(BASE), MANAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clerkApprovalLimit").value(2500.00))
                .andExpect(jsonPath("$.currencyCode").value("USD"))
                .andExpect(jsonPath("$.defaultTerms").value("NET30"));
        mockMvc.perform(withAuth(
                        put(BASE)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"clerkApprovalLimit\":2500.00,\"currencyCode\":\"USD\","
                                        + "\"justification\":\"Routine parts bills\",\"requestId\":\"" + REQUEST
                                        + "\"}"),
                        MANAGE))
                .andExpect(status().isOk());

        mockMvc.perform(withAuth(get(BASE), "accounting:ap:approve,accounting:ap:view"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(withAuth(
                        put(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"), "accounting:ap:approve"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC12: a body carrying approvedBy or operatorId binds without it")
    void actorInTheBodyIsIgnored() throws Exception {
        when(policyService.set(any())).thenReturn(policy());

        mockMvc.perform(withAuth(
                        put(BASE)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"allowCreatorApproval\":true,\"justification\":\"Two-person office\","
                                        + "\"requestId\":\"" + REQUEST + "\",\"approvedBy\":\"someone-else\","
                                        + "\"operatorId\":\"someone-else\"}"),
                        MANAGE))
                .andExpect(status().isOk());

        ArgumentCaptor<ApApprovalPolicyRequest> body = ArgumentCaptor.forClass(ApApprovalPolicyRequest.class);
        verify(policyService).set(body.capture());
        org.assertj.core.api.Assertions.assertThat(body.getValue())
                .isEqualTo(
                        new ApApprovalPolicyRequest(null, null, null, true, null, null, "Two-person office", REQUEST));
    }

    @Test
    @DisplayName("AC8: VALIDATION_ERROR answers 400 with fieldErrors naming each field")
    void validationEnvelope() throws Exception {
        when(policyService.set(any()))
                .thenThrow(new VendorBillException(
                        VendorBillException.Code.VALIDATION_ERROR,
                        "The AP approval policy request is not valid: defaultTerms",
                        List.of(new VendorBillException.FieldError("defaultTerms", "must be DUE_ON_RECEIPT or NET<n>")),
                        null));

        mockMvc.perform(withAuth(
                        put(BASE)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"defaultTerms\":\"NET_30\",\"justification\":\"Ten chars plus\","
                                        + "\"requestId\":\"" + REQUEST + "\"}"),
                        MANAGE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("defaultTerms"));
    }

    @Test
    @DisplayName("No caller and no permission: nothing reaches the service")
    void forbiddenNeverReachesTheService() throws Exception {
        mockMvc.perform(withAuth(get(BASE).param("historyPage", "1"), "accounting:ap:pay"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(policyService);
        when(policyService.get(eq(1), eq(20))).thenReturn(policy());
        mockMvc.perform(withAuth(get(BASE).param("historyPage", "1"), MANAGE)).andExpect(status().isOk());
        verify(policyService).get(1, 20);
    }
}
