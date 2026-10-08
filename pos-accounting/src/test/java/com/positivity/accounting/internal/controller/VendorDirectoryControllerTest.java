package com.positivity.accounting.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.dto.VendorApSettingsRequest;
import com.positivity.accounting.internal.dto.VendorRemitToConfirmationRequest;
import com.positivity.accounting.internal.dto.VendorResponse;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.service.VendorDirectoryService;
import com.positivity.web.common.ReplicationPendingException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Tests for VendorDirectoryController (Issue #816): vendor name typeahead
 * search and single-vendor lookup, including permission enforcement.
 */
@DisplayName("VendorDirectoryController Tests")
class VendorDirectoryControllerTest extends BaseIntegrationTest {

    private static final UUID VENDOR_ID = UUID.fromString("01960003-0000-7000-8000-000000000001");

    @MockitoBean
    private VendorDirectoryService vendorDirectoryService;

    private static VendorResponse acme() {
        return VendorResponse.builder()
                .vendorId(VENDOR_ID)
                .name("Acme Auto Parts")
                .vendorNumber("V-000123")
                .status("ACTIVE")
                .remitToVersion(1)
                .build();
    }

    @Nested
    @DisplayName("GET /v1/accounting/vendors")
    class SearchVendors {

        @Test
        @DisplayName("Should return name-matched vendors")
        void shouldReturnMatchedVendors() throws Exception {
            when(vendorDirectoryService.searchVendors(eq("acme"), any(), anyInt()))
                    .thenReturn(List.of(acme()));

            mockMvc.perform(withAuth(get("/v1/accounting/vendors").param("name", "acme")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].vendorId").value(VENDOR_ID.toString()))
                    .andExpect(jsonPath("$[0].name").value("Acme Auto Parts"))
                    .andExpect(jsonPath("$[0].vendorNumber").value("V-000123"))
                    .andExpect(jsonPath("$[0].status").value("ACTIVE"));
        }

        @Test
        @DisplayName("Should list vendors when no name term is given")
        void shouldListWithoutTerm() throws Exception {
            when(vendorDirectoryService.searchVendors(any(), any(), anyInt())).thenReturn(List.of(acme()));

            mockMvc.perform(withAuth(get("/v1/accounting/vendors")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].vendorId").value(VENDOR_ID.toString()));
        }

        @Test
        @DisplayName("Should reject search without accounting:ap:view authority")
        void shouldRejectWithoutPermission() throws Exception {
            mockMvc.perform(withAuth(get("/v1/accounting/vendors").param("name", "acme"), "accounting:je:view"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("GET /v1/accounting/vendors/{vendorId}")
    class GetVendorById {

        @Test
        @DisplayName("Should resolve a single vendor by id")
        void shouldResolveVendorById() throws Exception {
            when(vendorDirectoryService.getVendorById(VENDOR_ID)).thenReturn(acme());

            mockMvc.perform(withAuth(get("/v1/accounting/vendors/{vendorId}", VENDOR_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.vendorId").value(VENDOR_ID.toString()))
                    .andExpect(jsonPath("$.name").value("Acme Auto Parts"));
        }

        @Test
        @DisplayName(
                "S24: a vendor not in the copy yet is 503 VENDOR_REPLICATION_PENDING with Retry-After (ADR-0017 §1)")
        void shouldReturn503ForVendorNotCopiedYet() throws Exception {
            when(vendorDirectoryService.getVendorById(VENDOR_ID)).thenThrow(pending());

            mockMvc.perform(withAuth(get("/v1/accounting/vendors/{vendorId}", VENDOR_ID)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().exists("Retry-After"))
                    .andExpect(jsonPath("$.code").value("VENDOR_REPLICATION_PENDING"))
                    .andExpect(jsonPath("$.status").value(503));
        }

        @Test
        @DisplayName("Should reject lookup without accounting:ap:view authority")
        void shouldRejectWithoutPermission() throws Exception {
            mockMvc.perform(withAuth(get("/v1/accounting/vendors/{vendorId}", VENDOR_ID), "accounting:je:view"))
                    .andExpect(status().isForbidden());
        }
    }

    private static ReplicationPendingException pending() {
        return new ReplicationPendingException(
                "VENDOR_REPLICATION_PENDING", "The vendor is not in accounting's copy yet; retry", VENDOR_ID);
    }

    @Nested
    @DisplayName("POST /v1/accounting/vendors/{vendorId}/remit-to-confirmation (S24)")
    class ConfirmRemitTo {

        private static final String URL = "/v1/accounting/vendors/{vendorId}/remit-to-confirmation";
        private static final String BODY =
                "{\"remitToVersion\":3,\"justification\":\"Called the vendor; address verified\"}";

        private org.springframework.test.web.servlet.ResultActions confirm(String authorities) throws Exception {
            return mockMvc.perform(withAuth(post(URL, VENDOR_ID), authorities)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY));
        }

        @Test
        @DisplayName("200 with the vendor read under accounting:ap:approve")
        void confirms() throws Exception {
            when(vendorDirectoryService.confirmRemitTo(eq(VENDOR_ID), any())).thenReturn(acme());

            confirm("accounting:ap:approve")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.vendorId").value(VENDOR_ID.toString()));

            ArgumentCaptor<VendorRemitToConfirmationRequest> body =
                    ArgumentCaptor.forClass(VendorRemitToConfirmationRequest.class);
            verify(vendorDirectoryService).confirmRemitTo(eq(VENDOR_ID), body.capture());
            assertThat(body.getValue().remitToVersion()).isEqualTo(3);
        }

        @Test
        @DisplayName("403 without accounting:ap:approve (ap:view, ap:pay, ap_approval_policy:manage are not it)")
        void permissionMatrix() throws Exception {
            for (String held :
                    new String[] {"accounting:ap:view", "accounting:ap:pay", "accounting:ap_approval_policy:manage"}) {
                confirm(held).andExpect(status().isForbidden());
            }
            verify(vendorDirectoryService, never()).confirmRemitTo(any(), any());
        }

        @Test
        @DisplayName("403 VENDOR_REMIT_TO_SELF_CONFIRMATION for the remit-to's requester")
        void selfConfirmation() throws Exception {
            when(vendorDirectoryService.confirmRemitTo(eq(VENDOR_ID), any()))
                    .thenThrow(new VendorBillException(
                            VendorBillException.Code.VENDOR_REMIT_TO_SELF_CONFIRMATION,
                            "You requested vendor V-000123's current remit-to; another approver confirms it"));

            confirm("accounting:ap:approve")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("VENDOR_REMIT_TO_SELF_CONFIRMATION"));
        }

        @Test
        @DisplayName("400 VALIDATION_ERROR and JUSTIFICATION_REQUIRED, 409 for a stale version, 503 not copied yet")
        void refusals() throws Exception {
            when(vendorDirectoryService.confirmRemitTo(eq(VENDOR_ID), any()))
                    .thenThrow(new VendorBillException(
                            VendorBillException.Code.VALIDATION_ERROR, "remitToVersion is required"))
                    .thenThrow(new VendorBillException(
                            VendorBillException.Code.JUSTIFICATION_REQUIRED, "justification is required"))
                    .thenThrow(new VendorBillException(
                            VendorBillException.Code.VENDOR_PAYMENT_DETAILS_CHANGED, "not the current version"))
                    .thenThrow(pending());

            confirm("accounting:ap:approve")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            confirm("accounting:ap:approve")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("JUSTIFICATION_REQUIRED"));
            confirm("accounting:ap:approve")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("VENDOR_PAYMENT_DETAILS_CHANGED"));
            confirm("accounting:ap:approve")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().exists("Retry-After"))
                    .andExpect(jsonPath("$.code").value("VENDOR_REPLICATION_PENDING"));
        }
    }

    @Nested
    @DisplayName("PUT /v1/accounting/vendors/{vendorId}/ap-settings (S24)")
    class SetApSettings {

        private static final String URL = "/v1/accounting/vendors/{vendorId}/ap-settings";
        private static final String MANAGE = "accounting:ap_approval_policy:manage";
        private static final String TAIL =
                "\"justification\":\"Shop supplies by default\",\"requestId\":\"0199c0de-7a1b-7c2d-8e3f-4a5b6c7d8e9f\"";

        private org.springframework.test.web.servlet.ResultActions set(String authorities, String json)
                throws Exception {
            return mockMvc.perform(withAuth(put(URL, VENDOR_ID), authorities)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json));
        }

        private VendorApSettingsRequest sent() {
            ArgumentCaptor<VendorApSettingsRequest> body = ArgumentCaptor.forClass(VendorApSettingsRequest.class);
            verify(vendorDirectoryService).setApSettings(eq(VENDOR_ID), body.capture());
            return body.getValue();
        }

        @Test
        @DisplayName("403 without accounting:ap_approval_policy:manage (ap:approve, ap:view are not it)")
        void permissionMatrix() throws Exception {
            for (String held : new String[] {"accounting:ap:approve", "accounting:ap:view", "accounting:ap:pay"}) {
                set(held, "{" + TAIL + "}").andExpect(status().isForbidden());
            }
            verify(vendorDirectoryService, never()).setApSettings(any(), any());
        }

        @Test
        @DisplayName("both fields absent: neither is present in the request the service sees")
        void absent() throws Exception {
            when(vendorDirectoryService.setApSettings(eq(VENDOR_ID), any())).thenReturn(acme());

            set(MANAGE, "{" + TAIL + "}").andExpect(status().isOk());

            VendorApSettingsRequest request = sent();
            assertThat(request.hasDefaultDebitClass()).isFalse();
            assertThat(request.hasDefaultExpenseMappingKey()).isFalse();
        }

        @Test
        @DisplayName("both fields null: both present and null (clear)")
        void explicitNull() throws Exception {
            when(vendorDirectoryService.setApSettings(eq(VENDOR_ID), any())).thenReturn(acme());

            set(MANAGE, "{\"defaultDebitClass\":null,\"defaultExpenseMappingKey\":null," + TAIL + "}")
                    .andExpect(status().isOk());

            VendorApSettingsRequest request = sent();
            assertThat(request.hasDefaultDebitClass()).isTrue();
            assertThat(request.getDefaultDebitClass()).isNull();
            assertThat(request.hasDefaultExpenseMappingKey()).isTrue();
            assertThat(request.getDefaultExpenseMappingKey()).isNull();
        }

        @Test
        @DisplayName("both fields with values: present with their values")
        void values() throws Exception {
            when(vendorDirectoryService.setApSettings(eq(VENDOR_ID), any())).thenReturn(acme());

            set(
                            MANAGE,
                            "{\"defaultDebitClass\":\"EXPENSE\",\"defaultExpenseMappingKey\":\"EXPENSE_SHOP_SUPPLIES\","
                                    + TAIL + "}")
                    .andExpect(status().isOk());

            VendorApSettingsRequest request = sent();
            assertThat(request.getDefaultDebitClass()).isEqualTo("EXPENSE");
            assertThat(request.getDefaultExpenseMappingKey()).isEqualTo("EXPENSE_SHOP_SUPPLIES");
        }

        @Test
        @DisplayName("an unknown property is 400 and never reaches the service")
        void unknownProperty() throws Exception {
            set(MANAGE, "{\"acceptTaxOnResaleGoods\":true," + TAIL + "}").andExpect(status().isBadRequest());
            verify(vendorDirectoryService, never()).setApSettings(any(), any());
        }

        @Test
        @DisplayName("400 VALIDATION_ERROR with fieldErrors, 400 JUSTIFICATION_REQUIRED, 409, 503 not copied yet")
        void refusals() throws Exception {
            when(vendorDirectoryService.setApSettings(eq(VENDOR_ID), any()))
                    .thenThrow(new VendorBillException(
                            VendorBillException.Code.VALIDATION_ERROR,
                            "The vendor AP settings request is not valid: defaultExpenseMappingKey",
                            List.of(new VendorBillException.FieldError(
                                    "defaultExpenseMappingKey", "must name an active VENDOR_BILL expense key")),
                            null))
                    .thenThrow(new VendorBillException(
                            VendorBillException.Code.JUSTIFICATION_REQUIRED, "justification is required"))
                    .thenThrow(new com.positivity.accounting.internal.exception.IdempotencyConflictException(
                            "requestId was already used for another change"))
                    .thenThrow(pending());

            set(MANAGE, "{" + TAIL + "}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("defaultExpenseMappingKey"));
            set(MANAGE, "{" + TAIL + "}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("JUSTIFICATION_REQUIRED"));
            set(MANAGE, "{" + TAIL + "}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
            set(MANAGE, "{" + TAIL + "}")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().exists("Retry-After"))
                    .andExpect(jsonPath("$.code").value("VENDOR_REPLICATION_PENDING"));
        }
    }
}
