package com.positivity.accounting.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.dto.ApPayFromAccountListResponse;
import com.positivity.accounting.internal.dto.VendorBillExpenseCategoryListResponse;
import com.positivity.accounting.internal.service.ApChoicesService;
import com.positivity.accounting.internal.service.VendorBillApprovalService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * AP reads #2670 AC 3: the expense-category read answers on {@code accounting:ap:view} and is never routed as a bill
 * id; the pay-from read answers on {@code accounting:ap:pay}; each is 403 without its permission.
 */
@DisplayName("AP choice reads: expense categories and pay-from accounts (#2670)")
class ApChoicesControllerTest extends BaseIntegrationTest {

    private static final String CATEGORIES = "/v1/accounting/vendor-bills/expense-categories";
    private static final String PAY_FROM = "/v1/accounting/ap/pay-from-accounts";
    private static final UUID BANK = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f1000");

    @MockitoBean
    private ApChoicesService apChoicesService;

    @MockitoBean
    private VendorBillApprovalService approvalService;

    @Test
    @DisplayName("200 with accounting:ap:view alone (a clerk without mapping-key:view): the categories in server order")
    void categories() throws Exception {
        when(apChoicesService.expenseCategories())
                .thenReturn(new VendorBillExpenseCategoryListResponse(
                        LocalDate.of(2026, 10, 9),
                        List.of(
                                new VendorBillExpenseCategoryListResponse.Category(
                                        "EXPENSE_SHOP_SUPPLIES",
                                        "Shop supplies",
                                        "6340",
                                        "Shop Supplies & Consumables"),
                                new VendorBillExpenseCategoryListResponse.Category(
                                        "EXPENSE_STAFF_MEALS", "Staff meals", null, null))));

        mockMvc.perform(withAuth(get(CATEGORIES), "accounting:ap:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value("2026-10-09"))
                .andExpect(jsonPath("$.categories[0].mappingKey").value("EXPENSE_SHOP_SUPPLIES"))
                .andExpect(jsonPath("$.categories[0].label").value("Shop supplies"))
                .andExpect(jsonPath("$.categories[0].accountNumber").value("6340"))
                .andExpect(jsonPath("$.categories[1].mappingKey").value("EXPENSE_STAFF_MEALS"));
    }

    @Test
    @DisplayName("AC 3: the literal segment expense-categories is never handled as {billId}")
    void literalSegmentIsNotABillId() throws Exception {
        when(apChoicesService.expenseCategories())
                .thenReturn(new VendorBillExpenseCategoryListResponse(LocalDate.of(2026, 10, 9), List.of()));

        mockMvc.perform(withAuth(get(CATEGORIES), "accounting:ap:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories").isEmpty());
        verify(approvalService, never()).getBill(any());
    }

    @Test
    @DisplayName("AC 3: without accounting:ap:view the categories answer 403 FORBIDDEN")
    void categoriesNeedApView() throws Exception {
        mockMvc.perform(withAuth(get(CATEGORIES), "accounting:mapping-key:view"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(apChoicesService, never()).expenseCategories();
    }

    @Test
    @DisplayName("200 with accounting:ap:pay: the eligible accounts, the functional currency and the default")
    void payFrom() throws Exception {
        when(apChoicesService.payFromAccounts())
                .thenReturn(new ApPayFromAccountListResponse(
                        LocalDate.of(2026, 10, 9),
                        "USD",
                        BANK,
                        List.of(new ApPayFromAccountListResponse.Account(
                                BANK, "1000", "Operating Account", "First National", "4321"))));

        mockMvc.perform(withAuth(get(PAY_FROM), "accounting:ap:pay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value("2026-10-09"))
                .andExpect(jsonPath("$.currencyCode").value("USD"))
                .andExpect(jsonPath("$.defaultBankAccountId").value(BANK.toString()))
                .andExpect(jsonPath("$.accounts[0].bankAccountId").value(BANK.toString()))
                .andExpect(jsonPath("$.accounts[0].accountNumber").value("1000"))
                .andExpect(jsonPath("$.accounts[0].bankName").value("First National"))
                .andExpect(jsonPath("$.accounts[0].accountMask").value("4321"));
    }

    @Test
    @DisplayName("AC 3: without accounting:ap:pay (ap:view and reconciliation:view are not enough) the pay-from read"
            + " answers 403")
    void payFromNeedsApPay() throws Exception {
        mockMvc.perform(withAuth(get(PAY_FROM), "accounting:ap:view,accounting:reconciliation:view"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(apChoicesService, never()).payFromAccounts();
    }
}
