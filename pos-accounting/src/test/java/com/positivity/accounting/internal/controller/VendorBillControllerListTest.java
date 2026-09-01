package com.positivity.accounting.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.dto.VendorBillListRow;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.service.VendorBillService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Controller tests for {@link VendorBillController}'s due-date-window list route (Wave 2 E9,
 * issue #1597): happy path, status-filter delegation, unrecognized-status 400, window-too-wide
 * 400, and authorization.
 */
@DisplayName("VendorBillController listVendorBills Tests")
class VendorBillControllerListTest extends BaseIntegrationTest {

    private static final String LIST_PATH = "/v1/accounting/vendor-bills";

    @MockitoBean
    private VendorBillService vendorBillService;

    private VendorBillListRow stubRow() {
        return VendorBillListRow.builder()
                .billId(UUID.fromString("018f0000-0000-7000-8000-0000000000aa"))
                .vendorId(UUID.fromString("018f0000-0000-7000-8000-0000000000bb"))
                .dueDate(LocalDateTime.of(2026, 6, 15, 0, 0))
                .amount(new BigDecimal("1200.00"))
                .status(VendorBillStatus.APPROVED)
                .build();
    }

    @Test
    @DisplayName("GET / returns 200 with a page of bills for an authorized request")
    void listReturns200() throws Exception {
        when(vendorBillService.listByDueDateWindow(
                        eq(java.time.LocalDate.of(2026, 6, 1)),
                        eq(java.time.LocalDate.of(2026, 6, 30)),
                        isNull(),
                        any()))
                .thenReturn(new PageImpl<>(List.of(stubRow())));

        mockMvc.perform(withAuth(get(LIST_PATH).param("dueFrom", "2026-06-01").param("dueTo", "2026-06-30")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].amount").value(1200.00))
                .andExpect(jsonPath("$.content[0].status").value("APPROVED"));
    }

    @Test
    @DisplayName("GET / passes the status filter through to the service")
    void listPassesStatusFilter() throws Exception {
        when(vendorBillService.listByDueDateWindow(any(), any(), eq(VendorBillStatus.APPROVED), any()))
                .thenReturn(new PageImpl<>(List.of(stubRow())));

        mockMvc.perform(withAuth(get(LIST_PATH)
                        .param("dueFrom", "2026-06-01")
                        .param("dueTo", "2026-06-30")
                        .param("status", "APPROVED")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET / rejects an unrecognized status value with 400, not an empty page")
    void listRejectsUnrecognizedStatus() throws Exception {
        mockMvc.perform(withAuth(get(LIST_PATH)
                        .param("dueFrom", "2026-06-01")
                        .param("dueTo", "2026-06-30")
                        .param("status", "NOT_A_REAL_STATUS")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET / rejects dueTo before dueFrom with 400 VALIDATION_ERROR")
    void listRejectsInvalidRange() throws Exception {
        when(vendorBillService.listByDueDateWindow(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("dueTo cannot be before dueFrom"));

        mockMvc.perform(withAuth(get(LIST_PATH).param("dueFrom", "2026-06-30").param("dueTo", "2026-06-01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET / rejects a window wider than 366 days with 400 VALIDATION_ERROR")
    void listRejectsWindowTooWide() throws Exception {
        when(vendorBillService.listByDueDateWindow(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Due-date window cannot exceed 366 days"));

        mockMvc.perform(withAuth(get(LIST_PATH).param("dueFrom", "2025-01-01").param("dueTo", "2026-06-30")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET / returns 403 when the caller lacks accounting:ap:view")
    void listReturns403WithoutPermission() throws Exception {
        mockMvc.perform(withAuth(
                        get(LIST_PATH).param("dueFrom", "2026-06-01").param("dueTo", "2026-06-30"),
                        "accounting:je:view"))
                .andExpect(status().isForbidden());
    }
}
