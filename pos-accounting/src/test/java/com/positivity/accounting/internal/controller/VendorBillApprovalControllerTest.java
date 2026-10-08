package com.positivity.accounting.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseControllerSliceTest;
import com.positivity.accounting.internal.dto.VendorBillCommands;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorBillReview;
import com.positivity.accounting.internal.enums.VendorBillDifferenceClass;
import com.positivity.accounting.internal.enums.VendorBillStage;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.service.VendorBillApprovalService;
import com.positivity.security.common.GatewaySecurityConfig;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The approval endpoints of #2509 through the production security chain and exception handler: the permission
 * matrix per endpoint (G8, §4.3; AC10: accounting:ap:pay alone is refused everywhere), the error envelope of each
 * refusal code, and a body's operatorId ignored (ADR-0018).
 */
@DisplayName("VendorBillApprovalController: permission matrix and error codes (#2509)")
@WebMvcTest(VendorBillApprovalController.class)
@Import({GatewaySecurityConfig.class, WebCommonErrorAutoConfiguration.class, BaseControllerSliceTest.SliceConfig.class})
class VendorBillApprovalControllerTest extends BaseControllerSliceTest {

    private static final String BASE = "/v1/accounting/vendor-bills";
    private static final UUID BILL_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a01");
    private static final UUID CANDIDATE_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a21");
    private static final String APPROVE = "accounting:ap:approve";
    private static final String OVER_LIMIT = "accounting:ap:approve_over_limit";
    private static final String REJECT = "accounting:ap:reject";
    private static final String PAY = "accounting:ap:pay";
    private static final String VIEW = "accounting:ap:view";

    @MockitoBean
    private VendorBillApprovalService approvalService;

    private static VendorBillResponse awaiting() {
        return VendorBillResponse.builder()
                .vendorBillId(BILL_ID)
                .billNumber("INV-1")
                .status(VendorBillStatus.AWAITING_APPROVAL)
                .totalAmount(new BigDecimal("412.00"))
                .openAmount(new BigDecimal("412.00"))
                .lines(List.of())
                .checks(List.of())
                .availableActions(List.of())
                .build();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** Each endpoint with a valid body, and the permissions that pass its gate. */
    static Stream<Arguments> endpoints() {
        return Stream.of(
                Arguments.of(
                        "submit",
                        json(
                                post(BASE + "/" + BILL_ID + "/submit-for-approval"),
                                "{\"justification\":\"No delivery!\"}"),
                        List.of(APPROVE, OVER_LIMIT)),
                Arguments.of("approve", json(post(BASE + "/" + BILL_ID + "/approve"), "{}"), List.of(OVER_LIMIT)),
                Arguments.of(
                        "reject",
                        json(post(BASE + "/" + BILL_ID + "/reject"), "{\"reason\":\"Wrong vendor\"}"),
                        List.of(REJECT)),
                Arguments.of(
                        "resolve",
                        json(
                                post(BASE + "/" + BILL_ID + "/resolve-exception"),
                                "{\"resolutionAction\":\"CORRECT\",\"reason\":\"Recount the delivery\"}"),
                        List.of(APPROVE, OVER_LIMIT, REJECT)),
                Arguments.of(
                        "select",
                        post(BASE + "/match-candidates/" + CANDIDATE_ID + "/select"),
                        List.of(APPROVE, OVER_LIMIT)),
                Arguments.of(
                        "void",
                        json(post(BASE + "/" + BILL_ID + "/void"), "{\"reason\":\"Billed twice by mistake\"}"),
                        // AW45: every void needs ap:reject alone at the gate; an approved bill's approval tier is
                        // the service's check, once it knows the status.
                        List.of(REJECT, REJECT + "," + OVER_LIMIT)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("AC10: a caller holding only accounting:ap:pay (or ap:view) is 403 on every decision")
    void payOnlyIsForbidden(String name, MockHttpServletRequestBuilder request, List<String> allowed) throws Exception {
        mockMvc.perform(withAuth(request, PAY + "," + VIEW))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(approvalService);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("Each endpoint's gate admits each permission set that may call it")
    void gateAdmitsItsPermissions(String name, MockHttpServletRequestBuilder ignored, List<String> allowed)
            throws Exception {
        when(approvalService.submitForApproval(any(), any())).thenReturn(awaiting());
        when(approvalService.approve(any(), any())).thenReturn(awaiting());
        when(approvalService.reject(any(), any())).thenReturn(awaiting());
        when(approvalService.resolveException(any(), any())).thenReturn(awaiting());
        when(approvalService.selectCandidate(any())).thenReturn(awaiting());
        when(approvalService.voidBill(any(), any())).thenReturn(awaiting());
        for (String authorities : allowed) {
            MockHttpServletRequestBuilder request = endpoints()
                    .filter(a -> a.get()[0].equals(name))
                    .map(a -> (MockHttpServletRequestBuilder) a.get()[1])
                    .findFirst()
                    .orElseThrow();
            mockMvc.perform(withAuth(request, authorities))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"));
        }
    }

    @Test
    @DisplayName("AC6: a resolve-exception body carrying operatorId binds without it; the service never sees an actor")
    void operatorIdIsIgnored() throws Exception {
        when(approvalService.resolveException(eq(BILL_ID), any())).thenReturn(awaiting());

        mockMvc.perform(withAuth(
                        json(
                                post(BASE + "/" + BILL_ID + "/resolve-exception"),
                                "{\"resolutionAction\":\"CORRECT\",\"reason\":\"Recount the delivery\","
                                        + "\"operatorId\":\"someone-else\"}"),
                        APPROVE))
                .andExpect(status().isOk());

        ArgumentCaptor<VendorBillCommands.ResolveException> body =
                ArgumentCaptor.forClass(VendorBillCommands.ResolveException.class);
        verify(approvalService).resolveException(eq(BILL_ID), body.capture());
        org.assertj.core.api.Assertions.assertThat(body.getValue())
                .isEqualTo(
                        new VendorBillCommands.ResolveException("CORRECT", "Recount the delivery", null, null, null));
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of(VendorBillException.Code.VENDOR_BILL_NOT_FOUND, 404),
                Arguments.of(VendorBillException.Code.AP_BILL_NOT_APPROVABLE, 409),
                Arguments.of(VendorBillException.Code.AP_BILL_NOT_VOIDABLE, 409),
                Arguments.of(VendorBillException.Code.AP_MATCH_CANDIDATE_ALREADY_RESOLVED, 409),
                Arguments.of(VendorBillException.Code.AP_BILL_AWAITING_INVOICE, 409),
                Arguments.of(VendorBillException.Code.AP_BILL_ENTRY_NOT_REVERSIBLE, 409),
                Arguments.of(VendorBillException.Code.AP_BILL_UNCLASSIFIED, 422),
                Arguments.of(VendorBillException.Code.AP_BILL_TOTALS_UNRECONCILED, 422),
                Arguments.of(VendorBillException.Code.AP_BILL_ZERO_TOTAL, 422),
                Arguments.of(VendorBillException.Code.JUSTIFICATION_REQUIRED, 400),
                Arguments.of(VendorBillException.Code.VALIDATION_ERROR, 400));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("refusals")
    @DisplayName("Each refusal answers its status and code (ADR-0017)")
    void refusalEnvelope(VendorBillException.Code code, int httpStatus) throws Exception {
        when(approvalService.approve(eq(BILL_ID), any())).thenThrow(new VendorBillException(code, "refused"));

        mockMvc.perform(withAuth(json(post(BASE + "/" + BILL_ID + "/approve"), "{}"), OVER_LIMIT))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(code.name()));
    }

    @Test
    @DisplayName("L2: the void gate is accounting:ap:reject; the approval tier alone never passes it")
    void voidGateIsReject() throws Exception {
        mockMvc.perform(withAuth(
                        json(post(BASE + "/" + BILL_ID + "/void"), "{\"reason\":\"Billed twice by mistake\"}"),
                        APPROVE + "," + OVER_LIMIT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(approvalService);
    }

    @Test
    @DisplayName("B-M5: the service's own 403 (an approved bill's void without the approval tier) answers FORBIDDEN")
    void serviceRefusalIsForbidden() throws Exception {
        when(approvalService.voidBill(eq(BILL_ID), any()))
                .thenThrow(new AccessDeniedException("The caller may not VOID_APPROVED vendor bills"));

        mockMvc.perform(withAuth(
                        json(post(BASE + "/" + BILL_ID + "/void"), "{\"reason\":\"Billed twice by mistake\"}"), REJECT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("AW47: difference binds from JSON with its key \"class\"")
    void differenceBindsItsClass() throws Exception {
        when(approvalService.submitForApproval(eq(BILL_ID), any())).thenReturn(awaiting());

        mockMvc.perform(withAuth(
                        json(
                                post(BASE + "/" + BILL_ID + "/submit-for-approval"),
                                "{\"justification\":\"Totals checked with the vendor\",\"difference\":"
                                        + "{\"class\":\"FREIGHT\",\"justification\":\"Freight on the invoice\"}}"),
                        APPROVE))
                .andExpect(status().isOk());

        ArgumentCaptor<VendorBillCommands.Submit> body = ArgumentCaptor.forClass(VendorBillCommands.Submit.class);
        verify(approvalService).submitForApproval(eq(BILL_ID), body.capture());
        org.assertj.core.api.Assertions.assertThat(body.getValue().difference())
                .isEqualTo(new VendorBillReview.Difference(
                        VendorBillDifferenceClass.FREIGHT, null, "Freight on the invoice"));
    }

    @Test
    @DisplayName("L3: a reason over 1000 characters is 400 ARGUMENT_NOT_VALID before the service is called")
    void oversizedReasonIsNotValid() throws Exception {
        mockMvc.perform(withAuth(
                        json(post(BASE + "/" + BILL_ID + "/reject"), "{\"reason\":\"" + "x".repeat(1001) + "\"}"),
                        REJECT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ARGUMENT_NOT_VALID"));
        verifyNoInteractions(approvalService);
    }

    @Test
    @DisplayName("GET /stages and GET /by-stage need accounting:ap:view; an unknown stage is 400")
    void stageReads() throws Exception {
        when(approvalService.stageCounts())
                .thenReturn(new VendorBillReview.StageCounts(4, 2, 7, 3, BaseControllerSliceTest.TEST_CLOCK.instant()));
        when(approvalService.listByStage(eq(VendorBillStage.APPROVE), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(withAuth(get(BASE + "/stages"), VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.check").value(4))
                .andExpect(jsonPath("$.approve").value(2))
                .andExpect(jsonPath("$.pay").value(7))
                .andExpect(jsonPath("$.done").value(3))
                .andExpect(jsonPath("$.asOf").exists());
        mockMvc.perform(withAuth(get(BASE + "/by-stage").param("stage", "APPROVE"), VIEW))
                .andExpect(status().isOk());
        mockMvc.perform(withAuth(get(BASE + "/by-stage").param("stage", "LATER"), VIEW))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withAuth(get(BASE + "/stages"), PAY)).andExpect(status().isForbidden());
    }
}
