## Scope

- Issue: #1124, Wave 6 research only.
- Source boundary: /home/n541342/IdeaProjects/durion-positivity-backend/pos-workorder only.
- Goal: source-verified anchors for:
  - workorder.estimate-promotion
  - workorder.status-lifecycle
  - workorder.approval-config
  - workorder.codes

## Verified source inventory table

| File | Why reviewed | Key lines |
| --- | --- | --- |
| pos-workorder/src/main/java/com/positivity/workorder/internal/controller/EstimateController.java | Promote endpoint path, permission, event id, response behavior | 53, 403-409, 429-453, 523-536 |
| pos-workorder/src/main/java/com/positivity/workorder/internal/service/PromotionValidationServiceImpl.java | Promotion precondition gates and thrown error codes | 63-107 |
| pos-workorder/src/main/java/com/positivity/workorder/internal/exception/PromotionValidationException.java | PromotionErrorCode token declarations | 33-68 |
| pos-workorder/src/main/java/com/positivity/workorder/internal/service/WorkorderServiceImpl.java | EST->WO number behavior, resulting workorder status, promotion validation call, approved-item copy | 65-67, 127-134, 145-163, 258-267, 312-314 |
| pos-workorder/src/main/java/com/positivity/workorder/internal/enums/EstimateStatus.java | EstimateStatus token list | 3-20 |
| pos-workorder/src/main/java/com/positivity/workorder/internal/enums/ApprovalStatus.java | ApprovalStatus token list | 7-22 |
| pos-workorder/src/main/java/com/positivity/workorder/internal/enums/WorkorderStatus.java | Transition map, canTransitionTo, helpers for start-eligible and terminal | 8-63 |
| pos-workorder/src/main/java/com/positivity/workorder/internal/controller/ApprovalConfigurationController.java | Approval config endpoints, permissions, event ids | 32, 41-47, 56-60, 78-83, 102-107, 122-127, 148-153 |
| pos-workorder/src/main/java/com/positivity/workorder/internal/service/ApprovalConfigurationServiceImpl.java | Applicable config specificity ordering | 76-99 |
| pos-workorder/src/main/java/com/positivity/workorder/internal/entity/ApprovalConfiguration.java | calculatePriority behavior, ApprovalMethod tokens, model fields | 44-47, 49-57, 63-65, 70-82, 84-97 |
| pos-workorder/src/main/java/com/positivity/workorder/internal/dto/ApprovalConfigurationRequest.java | Exposed approval-config request fields | 23-62 |
| pos-workorder/src/main/java/com/positivity/workorder/internal/dto/ApprovalConfigurationResponse.java | Exposed approval-config response fields | 22-60 |
| pos-workorder/src/main/java/com/positivity/workorder/internal/config/EventTypes.java | Registered event ids for estimate promote and approval-config operations | 212-215, 274-295 |
| pos-workorder/src/main/resources/permissions.yaml | Permission token registry | 5-12, 25-44, 87-105 |
| pos-workorder/src/main/java/com/positivity/workorder/internal/config/PermissionRegistration.java | Confirms permissions are loaded from permissions.yaml | 18 |

## estimate-promotion facts

- Endpoint and security/event anchors:
  - Controller base path is /v1/workorders/estimates (EstimateController.java:53).
  - Promote endpoint is POST /{estimateId}/promote (EstimateController.java:403).
  - Event annotation is WORKORDER_ESTIMATE_PROMOTE (EstimateController.java:404).
  - Permission gate is hasAuthority('workorder:estimate:promote') (EstimateController.java:408).
- Promote call path:
  - Controller delegates to workorderService.createWorkorder(estimateId, null) (EstimateController.java:429).
  - ALREADY_PROMOTED is treated as idempotent retry when existingWorkorderId is present (EstimateController.java:523-536).
  - Other promotion validation failures return 409 (EstimateController.java:447-453).
- PromotionValidationServiceImpl gate chain (in-order):
  - Gate 1: estimate must exist, else ESTIMATE_NOT_FOUND (PromotionValidationServiceImpl.java:67-70).
  - Gate 2: estimate must not already map to a workorder, else ALREADY_PROMOTED (+ existingWorkorderId) (PromotionValidationServiceImpl.java:73-80).
  - Gate 3: estimate status must be APPROVED, else APPROVAL_INVALID (PromotionValidationServiceImpl.java:83-88).
  - Gate 4: approval must not be expired, else APPROVAL_EXPIRED (PromotionValidationServiceImpl.java:92-96).
  - Gate 5: at least one approved item required, else NO_APPROVED_ITEMS (PromotionValidationServiceImpl.java:99-107).
- Workorder creation behavior during promote:
  - New workorder status is initialized to DRAFT (WorkorderServiceImpl.java:133).
  - Number behavior: if estimate number starts with EST-, swap to WO- and use if free; else fallback to WO-YYYY-NNNN sequence (WorkorderServiceImpl.java:145-163).
  - Estimate-derived creation still reruns promotionValidationService.validatePromotionPreconditions before save (WorkorderServiceImpl.java:258-267).
  - Only APPROVED estimate items are copied into workorder lines (WorkorderServiceImpl.java:312-314).
- Token sets requested:
  - PromotionErrorCode declared: ALREADY_PROMOTED, APPROVAL_EXPIRED, APPROVAL_INVALID, APPROVAL_NOT_FOUND, ESTIMATE_NOT_FOUND, NO_APPROVED_ITEMS, INVALID_STATE (PromotionValidationException.java:33-68).
  - EstimateStatus declared: DRAFT, PENDING_APPROVAL, OPEN, PENDING_CUSTOMER, APPROVED, DECLINED, EXPIRED, SCHEDULED, INVOICED, CANCELLED, ARCHIVED (EstimateStatus.java:3-20).
  - ApprovalStatus declared: PENDING_APPROVAL, APPROVED, DECLINED (ApprovalStatus.java:7-22).

## status-lifecycle facts

- WorkorderStatus transition model:
  - Transition map is ALLOWED_TRANSITIONS (WorkorderStatus.java:19-32).
  - canTransitionTo(newStatus) checks membership in that map (WorkorderStatus.java:34-37).
- Declared transitions:
  - DRAFT -> APPROVED, CANCELLED.
  - APPROVED -> ASSIGNED, WORK_IN_PROGRESS, AWAITING_APPROVAL, CANCELLED.
  - ASSIGNED -> WORK_IN_PROGRESS, CANCELLED.
  - WORK_IN_PROGRESS -> AWAITING_PARTS, AWAITING_APPROVAL, READY_FOR_PICKUP, COMPLETED, CANCELLED.
  - AWAITING_PARTS -> WORK_IN_PROGRESS, COMPLETED, CANCELLED.
  - AWAITING_APPROVAL -> WORK_IN_PROGRESS, COMPLETED, CANCELLED.
  - READY_FOR_PICKUP -> COMPLETED, CANCELLED.
  - COMPLETED -> none.
  - CANCELLED -> none.
- Helper methods:
  - getStartEligibleStatuses() returns APPROVED and ASSIGNED (WorkorderStatus.java:43-45).
  - getTerminalStatuses() returns COMPLETED and CANCELLED (WorkorderStatus.java:52-53).
  - getOpenStatuses() is complement of terminal statuses (WorkorderStatus.java:60-62).

## approval-config facts

- Controller endpoints, permissions, and event ids:
  - Base path: /v1/workexec (ApprovalConfigurationController.java:32).
  - GET /v1/workexec emits WORKORDER_APPROVAL_CONFIG_LIST, permission workorder:approval_config:view (ApprovalConfigurationController.java:41-47).
  - GET /v1/workexec/approvalConfigurations/{approvalId}, permission workorder:approval_config:view (ApprovalConfigurationController.java:56-60).
  - GET /v1/workexec/approvalConfigurations/applicable, permission workorder:approval_config:view (ApprovalConfigurationController.java:78-83).
  - POST /v1/workexec/approvalConfigurations emits WORKORDER_APPROVAL_CONFIG_CREATE, permission workorder:approval_config:create (ApprovalConfigurationController.java:102-107).
  - PUT /v1/workexec/approvalConfigurations/{approvalId} emits WORKORDER_APPROVAL_CONFIG_UPDATE, permission workorder:approval_config:edit (ApprovalConfigurationController.java:122-127).
  - DELETE /v1/workexec/approvalConfigurations/{approvalId} emits WORKORDER_APPROVAL_CONFIG_DELETE, permission workorder:approval_config:delete (ApprovalConfigurationController.java:148-153).
- Applicable config specificity ordering:
  - getApplicableConfiguration(locationId, customerId) checks in this exact order:
    - customer-specific: findByLocationIdAndCustomerId(locationId, customerId)
    - location-specific: findByLocationIdAndCustomerIdIsNull(locationId)
    - global default: findByLocationIdIsNullAndCustomerIdIsNull()
  - Verified in ApprovalConfigurationServiceImpl.java:76-99.
- ApprovalConfiguration.calculatePriority details:
  - calculatePriority is called on PrePersist and PreUpdate (ApprovalConfiguration.java:44-47, 84-87).
  - Priority derivation is deterministic:
    - customerId present -> priority 2
    - else locationId present -> priority 1
    - else priority 0
  - Verified in ApprovalConfiguration.java:89-97.
- ApprovalMethod tokens:
  - CLICK_CONFIRM, SIGNATURE, ELECTRONIC_SIGNATURE, VERBAL_CONFIRMATION (ApprovalConfiguration.java:77-82).
- Monetary threshold clarification:
  - No monetary threshold field appears in approval-configuration request/response/entity for matching or authorization.
  - Matching is by locationId/customerId specificity plus calculated priority (ApprovalConfigurationServiceImpl.java:76-99, ApprovalConfiguration.java:70-75, 89-97).
  - There is an approvalWindowDays field (time-window semantics) in ApprovalConfiguration entity and it is consumed for estimate expiration on submit-for-approval (ApprovalConfiguration.java:63-65, EstimateServiceImpl.java:672-675).

## codes token catalog seed lists

- Permission token seeds (from permissions.yaml):
  - Promotion and estimate: workorder:estimate:view, workorder:estimate:create, workorder:estimate:submit, workorder:estimate:approve, workorder:estimate:decline, workorder:estimate:reopen, workorder:estimate:promote, workorder:estimate:edit, workorder:estimate:calculate.
  - Approval config: workorder:approval_config:view, workorder:approval_config:create, workorder:approval_config:edit, workorder:approval_config:delete.
  - Status-lifecycle read anchor: workorder:workorder:view.
- Event id seeds:
  - Promotion: WORKORDER_ESTIMATE_PROMOTE.
  - Approval config: WORKORDER_APPROVAL_CONFIG_LIST, WORKORDER_APPROVAL_CONFIG_CREATE, WORKORDER_APPROVAL_CONFIG_UPDATE, WORKORDER_APPROVAL_CONFIG_DELETE.
  - Registry confirmation in EventTypes.java:212-215, 274-295.
- Enum token seeds:
  - Promotion errors: ALREADY_PROMOTED, APPROVAL_EXPIRED, APPROVAL_INVALID, APPROVAL_NOT_FOUND, ESTIMATE_NOT_FOUND, NO_APPROVED_ITEMS, INVALID_STATE.
  - Estimate statuses: DRAFT, PENDING_APPROVAL, OPEN, PENDING_CUSTOMER, APPROVED, DECLINED, EXPIRED, SCHEDULED, INVOICED, CANCELLED, ARCHIVED.
  - Approval statuses: PENDING_APPROVAL, APPROVED, DECLINED.
  - Workorder statuses: DRAFT, APPROVED, ASSIGNED, WORK_IN_PROGRESS, AWAITING_PARTS, AWAITING_APPROVAL, READY_FOR_PICKUP, COMPLETED, CANCELLED.
  - Approval methods: CLICK_CONFIRM, SIGNATURE, ELECTRONIC_SIGNATURE, VERBAL_CONFIRMATION.

## declared-but-unused/mismatch notes

- PromotionErrorCode declaration vs throw sites:
  - Declared in enum: APPROVAL_NOT_FOUND and INVALID_STATE (PromotionValidationException.java:52, 67).
  - No throw/reference to PromotionErrorCode.APPROVAL_NOT_FOUND or PromotionErrorCode.INVALID_STATE found outside declaration.
  - PromotionValidationServiceImpl throws only: ESTIMATE_NOT_FOUND, ALREADY_PROMOTED, APPROVAL_INVALID, APPROVAL_EXPIRED, NO_APPROVED_ITEMS (PromotionValidationServiceImpl.java:69-107).
- Event id mismatch:
  - Controller emits WORKORDER_ESTIMATE_PATCH on PATCH /{estimateId} (EstimateController.java:236-237).
  - No WORKORDER_ESTIMATE_PATCH registration found in EventTypes.java.
- Approval configuration priority input behavior:
  - ApprovalConfigurationServiceImpl accepts request.priority on create/update (ApprovalConfigurationServiceImpl.java:43, 61).
  - Entity lifecycle hooks recompute priority from customerId/locationId on persist/update (ApprovalConfiguration.java:44-47, 84-97), effectively overriding client-provided priority.
- Permission declaration vs hasAuthority usage snapshot (module-wide check):
  - Declared in permissions.yaml but no direct hasAuthority usage found in Java source for: workorder:estimate_item:view, workorder:estimate_snapshot:view, workorder:invoice:create, workorder:invoice:view, workorder:labor:add_on_behalf, workorder:wip:view_all_locations, workorder:workorder:create, workorder:workorder:delete, workorder:workorder:edit, workorder:workorder:start.
  - Used in hasAuthority but not declared in local permissions.yaml: inventory:pick_list:view, inventory:pick_list:execute, timekeeping:work_session:create, timekeeping:work_session:stop, timekeeping:work_session:break_start, timekeeping:work_session:break_stop.

## open risks/ambiguities

- PromotionErrorCode includes extra values not exercised by promotion validation flow. If downstream docs or clients depend on the full enum list, they may assume unsupported error paths.
- ApprovalConfiguration has approvalWindowDays at entity level, but request/response DTOs do not expose it. That can create API/operator confusion about where expiration behavior is configured.
- Priority field is accepted in request DTO and service assignment, but persistence hooks recompute it; this can appear as non-deterministic API behavior unless explicitly documented.
- Permission mismatch between local YAML and hasAuthority checks indicates some tokens are cross-module authorities or reserved for non-annotation checks; this should be validated before using these tokens as strict completeness checks in RAG docs.