package com.positivity.workorder.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.workorder.config.TestSecurityConfig;
import com.positivity.workorder.internal.config.GlobalExceptionHandler;
import com.positivity.workorder.internal.controller.WorkorderPickFacadeController;
import com.positivity.workorder.internal.dto.pick.CompletePickTaskRequest;
import com.positivity.workorder.internal.dto.pick.ConfirmPickLineRequest;
import com.positivity.workorder.internal.dto.pick.ResolveScanRequest;
import com.positivity.workorder.internal.dto.pick.ResolveScanResponse;
import com.positivity.workorder.internal.dto.pick.WorkorderPickListResponse;
import com.positivity.workorder.internal.dto.pick.WorkorderPickTaskResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * Controller-layer tests for CAP-218 pick facade endpoints in
 * {@link WorkorderPickFacadeController}.
 *
 * <p>
 * Covers HTTP routing, 200/404 service responses, and @PreAuthorize 403
 * enforcement for the pick-list and pick-task endpoints.
 *
 * Issue: CAP-218
 */
@WebMvcTest(
        controllers = WorkorderPickFacadeController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = GlobalExceptionHandler.class))
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
class WorkorderPickFacadeControllerTest {

    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000218");
    private static final UUID PICK_TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000219");
    // pickLineId aliases pickTaskId while lines are aliased to tasks (facade v1 contract)
    private static final UUID PICK_LINE_ID = PICK_TASK_ID;

    private static final String PICK_LIST_URL = "/v1/workorders/{workorderId}/pick-list";
    private static final String PICK_TASKS_URL = "/v1/workorders/{workorderId}/pick-list/tasks";
    private static final String RESOLVE_SCAN_URL = "/v1/workorders/{workorderId}/pick-tasks/{pickTaskId}:resolve-scan";
    private static final String CONFIRM_LINE_URL = "/v1/workorders/{workorderId}/pick-tasks/{pickTaskId}/lines/{pickLineId}:confirm";
    private static final String COMPLETE_TASK_URL = "/v1/workorders/{workorderId}/pick-tasks/{pickTaskId}:complete";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private WorkorderPickFacadeService workorderPickFacadeService;

    // -------------------------------------------------------------------------
    // AC-1: GET /pick-list → 200 when service returns a pick list response
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-1: GET /pick-list returns 200 with pick list body")
    void getPickList_whenServiceReturnsResponse_returns200() throws Exception {
        // Issue CAP-218: happy path — service returns a WorkorderPickListResponse
        var response = WorkorderPickListResponse.builder()
                .pickListId(UUID.randomUUID())
                .workorderId(WORKORDER_ID)
                .status("OPEN")
                .priority(1)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        when(workorderPickFacadeService.getPickListForWorkorder(WORKORDER_ID))
                .thenReturn(response);

        mockMvc.perform(get(PICK_LIST_URL, WORKORDER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workorderId").value(WORKORDER_ID.toString()))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    // -------------------------------------------------------------------------
    // AC-2: GET /pick-list → 404 when service throws NOT_FOUND
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-2: GET /pick-list returns 404 when workorder not found")
    void getPickList_whenWorkorderNotFound_returns404() throws Exception {
        // Issue CAP-218: service throws NOT_FOUND → controller propagates 404
        when(workorderPickFacadeService.getPickListForWorkorder(WORKORDER_ID))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Workorder not found"));

        mockMvc.perform(get(PICK_LIST_URL, WORKORDER_ID))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // AC-3: GET /pick-list → 403 when missing inventory:pick_list:view
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-3: GET /pick-list returns 403 when missing inventory:pick_list:view authority")
    void getPickList_whenMissingPickListViewAuthority_returns403() throws Exception {
        // Issue CAP-218: @PreAuthorize("hasAuthority('inventory:pick_list:view')") must deny
        mockMvc.perform(get(PICK_LIST_URL, WORKORDER_ID)
                .with(req -> {
                    req.setAttribute(TestSecurityConfig.NO_PICK_AUTH_ATTR, Boolean.TRUE);
                    return req;
                }))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // AC-4: GET /pick-list/tasks → 200 when service returns task list
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-4: GET /pick-list/tasks returns 200 with task list")
    void getPickTasks_whenServiceReturnsTasks_returns200() throws Exception {
        // Issue CAP-218: happy path — service returns a list of WorkorderPickTaskResponse
        var task = WorkorderPickTaskResponse.builder()
                .pickTaskId(PICK_TASK_ID)
                .pickListId(UUID.randomUUID())
                .status("PENDING")
                .requiredQty(3)
                .pickedQty(0)
                .remainingQty(3)
                .build();
        when(workorderPickFacadeService.getPickTasksForWorkorder(WORKORDER_ID))
                .thenReturn(List.of(task));

        mockMvc.perform(get(PICK_TASKS_URL, WORKORDER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pickTaskId").value(PICK_TASK_ID.toString()))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    // -------------------------------------------------------------------------
    // AC-5: POST /pick-tasks/{pickTaskId}:resolve-scan → 200 with matched response
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-5: POST :resolve-scan returns 200 with ResolveScanResponse")
    void resolveScan_whenServiceReturnsResponse_returns200() throws Exception {
        // Issue CAP-218: happy path — scan resolves to a matched SKU
        var skuId = UUID.randomUUID();
        var locationId = UUID.randomUUID();
        var request = ResolveScanRequest.builder()
                .scannedSkuId(skuId)
                .scannedLocationId(locationId)
                .build();
        var response = ResolveScanResponse.builder()
                .pickTaskId(PICK_TASK_ID)
                .pickListId(UUID.randomUUID())
                .resolvedSkuId(skuId)
                .resolvedLocationId(locationId)
                .matched(true)
                .matchStatus("MATCHED")
                .build();
        when(workorderPickFacadeService.resolveScan(eq(WORKORDER_ID), eq(PICK_TASK_ID), any()))
                .thenReturn(response);

        mockMvc.perform(post(RESOLVE_SCAN_URL, WORKORDER_ID, PICK_TASK_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(true))
                .andExpect(jsonPath("$.matchStatus").value("MATCHED"));
    }

    // -------------------------------------------------------------------------
    // AC-6: POST /pick-tasks/{pickTaskId}/lines/{pickLineId}:confirm → 200
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-6: POST :confirm returns 200 with updated WorkorderPickTaskResponse")
    void confirmPickLine_whenServiceReturnsResponse_returns200() throws Exception {
        // Issue CAP-218: happy path — pick line confirmed, returns updated task state
        var request = ConfirmPickLineRequest.builder()
                .quantityPicked(2)
                .build();
        var response = WorkorderPickTaskResponse.builder()
                .pickTaskId(PICK_TASK_ID)
                .status("IN_PROGRESS")
                .requiredQty(3)
                .pickedQty(2)
                .remainingQty(1)
                .version(0L)
                .build();
        when(workorderPickFacadeService.confirmPickLine(
                eq(WORKORDER_ID), eq(PICK_TASK_ID), eq(PICK_LINE_ID), any()))
                .thenReturn(response);

        mockMvc.perform(post(CONFIRM_LINE_URL, WORKORDER_ID, PICK_TASK_ID, PICK_LINE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.pickedQty").value(2));
    }

    // -------------------------------------------------------------------------
    // AC-7: POST /pick-tasks/{pickTaskId}:complete → 200
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-7: POST :complete returns 200 with completed WorkorderPickTaskResponse")
    void completePickTask_whenServiceReturnsResponse_returns200() throws Exception {
        // Issue CAP-218: happy path — pick task completed
        var request = CompletePickTaskRequest.builder()
                .reason("All items picked")
                .build();
        var response = WorkorderPickTaskResponse.builder()
                .pickTaskId(PICK_TASK_ID)
                .status("COMPLETED")
                .requiredQty(3)
                .pickedQty(3)
                .remainingQty(0)
                .version(0L)
                .build();
        when(workorderPickFacadeService.completePickTask(eq(WORKORDER_ID), eq(PICK_TASK_ID), any()))
                .thenReturn(response);

        mockMvc.perform(post(COMPLETE_TASK_URL, WORKORDER_ID, PICK_TASK_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.remainingQty").value(0));
    }

    // -------------------------------------------------------------------------
    // AC-8: POST :resolve-scan → 403 when missing inventory:pick_list:execute
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-8: POST :resolve-scan returns 403 when missing inventory:pick_list:execute authority")
    void resolveScan_whenMissingExecuteAuthority_returns403() throws Exception {
        // Issue CAP-218: @PreAuthorize("hasAuthority('inventory:pick_list:execute')") must deny
        var request = ResolveScanRequest.builder()
                .scannedSkuId(UUID.randomUUID())
                .scannedLocationId(UUID.randomUUID())
                .build();

        mockMvc.perform(post(RESOLVE_SCAN_URL, WORKORDER_ID, PICK_TASK_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .with(req -> {
                    req.setAttribute(TestSecurityConfig.NO_PICK_AUTH_ATTR, Boolean.TRUE);
                    return req;
                }))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // AC-9: POST :confirm → 403 when missing inventory:pick_list:execute
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-9: POST :confirm returns 403 when missing inventory:pick_list:execute authority")
    void confirmPickLine_whenMissingExecuteAuthority_returns403() throws Exception {
        // Issue CAP-218: @PreAuthorize("hasAuthority('inventory:pick_list:execute')") must deny
        var request = ConfirmPickLineRequest.builder()
                .quantityPicked(1)
                .build();

        mockMvc.perform(post(CONFIRM_LINE_URL, WORKORDER_ID, PICK_TASK_ID, PICK_LINE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .with(req -> {
                    req.setAttribute(TestSecurityConfig.NO_PICK_AUTH_ATTR, Boolean.TRUE);
                    return req;
                }))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // AC-10: POST :complete → 403 when missing inventory:pick_list:execute
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-10: POST :complete returns 403 when missing inventory:pick_list:execute authority")
    void completePickTask_whenMissingExecuteAuthority_returns403() throws Exception {
        // Issue CAP-218: @PreAuthorize("hasAuthority('inventory:pick_list:execute')") must deny
        var request = CompletePickTaskRequest.builder()
                .reason("test")
                .build();

        mockMvc.perform(post(COMPLETE_TASK_URL, WORKORDER_ID, PICK_TASK_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .with(req -> {
                    req.setAttribute(TestSecurityConfig.NO_PICK_AUTH_ATTR, Boolean.TRUE);
                    return req;
                }))
                .andExpect(status().isForbidden());
    }
}
