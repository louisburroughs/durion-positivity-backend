package com.positivity.accounting.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.dto.AutomaticWorkItem;
import com.positivity.accounting.internal.dto.AutomaticWorkItemsResponse;
import com.positivity.accounting.internal.dto.WorkItem;
import com.positivity.accounting.internal.dto.WorkItemAction;
import com.positivity.accounting.internal.dto.WorkItemPostingLine;
import com.positivity.accounting.internal.dto.WorkItemsResponse;
import com.positivity.accounting.internal.enums.AutomaticWorkItemKind;
import com.positivity.accounting.internal.enums.WorkItemArea;
import com.positivity.accounting.internal.enums.WorkItemType;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.exception.WorkItemsCursorInvalidException;
import com.positivity.accounting.internal.service.WorkItemAutomaticService;
import com.positivity.accounting.internal.service.WorkItemService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The work-item reads of #2515 through the production security chain: 200, the 400 codes, AC 12 (403 on both reads for
 * a caller holding none of the item permissions) and the annotations the event registry and the tools rely on.
 */
@DisplayName("WorkItemController (#2515)")
class WorkItemControllerTest extends BaseIntegrationTest {

    private static final String LIST = "/v1/accounting/work-items";
    private static final String AUTOMATIC = "/v1/accounting/work-items/automatic";

    @MockitoBean
    private WorkItemService service;

    @MockitoBean
    private WorkItemAutomaticService automaticService;

    private static WorkItemsResponse page() {
        UUID billId = UUID.fromString("0198a000-0000-7000-8000-000000000001");
        WorkItem item = new WorkItem(
                "BILL_AWAITING_APPROVAL:" + billId,
                WorkItemType.BILL_AWAITING_APPROVAL,
                WorkItemArea.BILLS,
                Map.of("vendor", "Acme Tires", "billNumber", "INV-1"),
                "CLERK",
                new BigDecimal("100.00"),
                "AWAITING_APPROVAL",
                LocalDate.of(2026, 10, 6),
                List.of(WorkItemAction.blocked("APPROVE", "AP_BILL_SELF_APPROVAL")),
                List.of(new WorkItemPostingLine(
                        "2000", "Accounts Payable", WorkItemPostingLine.Side.CREDIT, new BigDecimal("100.00"))),
                Map.of("vendorBillId", billId));
        Map<WorkItemArea, Integer> counts = new LinkedHashMap<>();
        counts.put(WorkItemArea.BILLS, 1);
        counts.put(WorkItemArea.PAYMENTS, 0);
        counts.put(WorkItemArea.BANK, 0);
        counts.put(WorkItemArea.CASH, 0);
        return new WorkItemsResponse(List.of(item), "next-cursor", counts, Instant.parse("2026-10-17T15:00:00Z"));
    }

    private static AutomaticWorkItemsResponse automaticPage() {
        UUID line = UUID.fromString("0198a000-0000-7000-8000-000000000002");
        return new AutomaticWorkItemsResponse(
                List.of(new AutomaticWorkItem(
                        "SETTLEMENT_LINE_MATCHED:" + line,
                        AutomaticWorkItemKind.SETTLEMENT_LINE_MATCHED,
                        LocalDate.of(2026, 10, 17),
                        Map.of("settlement", "SET-9"),
                        new BigDecimal("97.10"),
                        "MATCHED",
                        List.of(),
                        Map.of("settlementLineId", line))),
                null,
                Instant.parse("2026-10-17T15:00:00Z"));
    }

    @Test
    @DisplayName("200 with the page, the counts and the item's actions and preview")
    void list() throws Exception {
        when(service.list(isNull(), isNull(), isNull())).thenReturn(page());

        mockMvc.perform(withAuth(get(LIST), "accounting:ap:approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].type").value("BILL_AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.items[0].area").value("BILLS"))
                .andExpect(jsonPath("$.items[0].references.vendor").value("Acme Tires"))
                .andExpect(jsonPath("$.items[0].actions[0].allowed").value(false))
                .andExpect(jsonPath("$.items[0].actions[0].blockedReason").value("AP_BILL_SELF_APPROVAL"))
                .andExpect(jsonPath("$.items[0].postingPreview[0].side").value("CREDIT"))
                .andExpect(jsonPath("$.items[0].routing.vendorBillId").value("0198a000-0000-7000-8000-000000000001"))
                .andExpect(jsonPath("$.nextCursor").value("next-cursor"))
                .andExpect(jsonPath("$.countsByArea.BILLS").value(1))
                .andExpect(jsonPath("$.countsByArea.CASH").value(0))
                .andExpect(jsonPath("$.asOf").exists());
    }

    @Test
    @DisplayName("area, cursor and limit reach the service")
    void parameters() throws Exception {
        when(service.list(eq(WorkItemArea.BANK), eq("abc.def"), eq(10))).thenReturn(page());

        mockMvc.perform(withAuth(
                        get(LIST)
                                .param("area", "BANK")
                                .param("cursor", "abc.def")
                                .param("limit", "10"),
                        "accounting:reconciliation:approve"))
                .andExpect(status().isOk());

        verify(service).list(WorkItemArea.BANK, "abc.def", 10);
    }

    @Test
    @DisplayName("400 VALIDATION_ERROR on the limit field")
    void limitOutOfRange() throws Exception {
        when(service.list(any(), any(), eq(201)))
                .thenThrow(InvalidRequestParameterException.forField("limit", "must be from 1 to 200"));

        mockMvc.perform(withAuth(get(LIST).param("limit", "201"), "accounting:ap:pay"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("limit"));
    }

    @Test
    @DisplayName("400 WORK_ITEMS_CURSOR_INVALID for a cursor the service refuses")
    void cursorInvalid() throws Exception {
        when(service.list(any(), eq("tampered"), any()))
                .thenThrow(new WorkItemsCursorInvalidException("The cursor is not valid for this list"));

        mockMvc.perform(withAuth(get(LIST).param("cursor", "tampered"), "accounting:ap:pay"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WORK_ITEMS_CURSOR_INVALID"));
    }

    @Test
    @DisplayName("400 VALIDATION_ERROR on the area field when area is not one of BILLS, PAYMENTS, BANK, CASH")
    void unknownArea() throws Exception {
        mockMvc.perform(withAuth(get(LIST).param("area", "SPACE"), "accounting:ap:pay"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("area"));

        verify(service, never()).list(any(), any(), any());
    }

    @Test
    @DisplayName("AC12: 403 on the list for a caller holding none of the item permissions")
    void listForbidden() throws Exception {
        mockMvc.perform(withAuth(get(LIST), "accounting:coa:view")).andExpect(status().isForbidden());
        mockMvc.perform(withAuth(get(LIST), "accounting:ap:view")).andExpect(status().isForbidden());

        verify(service, never()).list(any(), any(), anyInt());
    }

    @Test
    @DisplayName("every item permission opens the list")
    void eachItemPermissionOpensTheList() throws Exception {
        when(service.list(isNull(), isNull(), isNull())).thenReturn(page());

        for (String permission : List.of(
                "accounting:ap:approve",
                "accounting:ap:approve_over_limit",
                "accounting:ap:pay",
                "accounting:payment:apply",
                "accounting:deposit:create",
                "accounting:reconciliation:adjust",
                "accounting:reconciliation:approve",
                "reporting:view:financial-statements",
                "accounting:je:post")) {
            mockMvc.perform(withAuth(get(LIST), permission)).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("each permission an automatic kind needs opens the automatic list; the list-only ones do not")
    void automaticPermissions() throws Exception {
        when(automaticService.list(isNull(), isNull(), isNull())).thenReturn(automaticPage());

        for (String permission : List.of(
                "accounting:payment:apply",
                "accounting:payment:reverse",
                "accounting:reconciliation:view",
                "accounting:ap:approve",
                "accounting:ap:approve_over_limit")) {
            mockMvc.perform(withAuth(get(AUTOMATIC), permission)).andExpect(status().isOk());
        }
        mockMvc.perform(withAuth(get(AUTOMATIC), "accounting:deposit:create")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("200 on the automatic list; since, cursor and limit reach the service")
    void automatic() throws Exception {
        when(automaticService.list(eq("2026-10-15"), eq("c.m"), eq(5))).thenReturn(automaticPage());

        mockMvc.perform(withAuth(
                        get(AUTOMATIC)
                                .param("since", "2026-10-15")
                                .param("cursor", "c.m")
                                .param("limit", "5"),
                        "accounting:payment:apply"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].kind").value("SETTLEMENT_LINE_MATCHED"))
                .andExpect(jsonPath("$.items[0].actions").isEmpty())
                .andExpect(jsonPath("$.items[0].references.settlement").value("SET-9"))
                .andExpect(jsonPath("$.asOf").exists());
    }

    @Test
    @DisplayName("400 VALIDATION_ERROR on the since field and WORK_ITEMS_CURSOR_INVALID on the automatic list")
    void automaticBadRequests() throws Exception {
        when(automaticService.list(eq("2026-01-01"), any(), any()))
                .thenThrow(InvalidRequestParameterException.forField("since", "must not be more than 31 days back"));
        when(automaticService.list(any(), eq("tampered"), any()))
                .thenThrow(new WorkItemsCursorInvalidException("The cursor is not valid for this list"));

        mockMvc.perform(withAuth(get(AUTOMATIC).param("since", "2026-01-01"), "accounting:payment:apply"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("since"));
        mockMvc.perform(withAuth(get(AUTOMATIC).param("cursor", "tampered"), "accounting:payment:apply"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WORK_ITEMS_CURSOR_INVALID"));
    }

    @Test
    @DisplayName("AC12: 403 on the automatic list for a caller holding none of the item permissions")
    void automaticForbidden() throws Exception {
        mockMvc.perform(withAuth(get(AUTOMATIC), "accounting:coa:view")).andExpect(status().isForbidden());

        verify(automaticService, never()).list(any(), any(), any());
    }

    @Test
    @DisplayName("operation ids and the ACCOUNTING_WORK_ITEMS_* events")
    void annotations() throws Exception {
        Method list =
                WorkItemController.class.getMethod("listWorkItems", String.class, String.class, Integer.class);
        Method automatic =
                WorkItemController.class.getMethod("listAutomaticWorkItems", String.class, String.class, Integer.class);

        assertThat(list.getAnnotation(Operation.class).operationId()).isEqualTo("listWorkItems");
        assertThat(list.getAnnotation(EmitEvent.class).id()).isEqualTo("ACCOUNTING_WORK_ITEMS_LIST");
        assertThat(automatic.getAnnotation(Operation.class).operationId()).isEqualTo("listAutomaticWorkItems");
        assertThat(automatic.getAnnotation(EmitEvent.class).id()).isEqualTo("ACCOUNTING_WORK_ITEMS_AUTOMATIC_LIST");
    }
}
