package com.positivity.workorder.internal.config;

import com.positivity.events.EventTypeRegistration;

import java.util.List;

/**
 * Registry of all event types for the Workorder module.
 * 
 * Event naming convention: WORKORDER_{RESOURCE}_{ACTION}
 */
public final class WorkorderEventTypes {

        private WorkorderEventTypes() {
                // Utility class
        }

        // ==================== WORKORDER EVENTS ====================

        /** List all workorders */
        public static final EventTypeRegistration WORKORDER_LIST = EventTypeRegistration.search("WORKORDER_LIST",
                        "Retrieve list of all workorders")
                        .apiVersion("1")
                        .build();

        /** Create a new workorder */
        public static final EventTypeRegistration WORKORDER_CREATE = EventTypeRegistration.write("WORKORDER_CREATE",
                        "Create a new workorder in the system")
                        .apiVersion("1")
                        .build();

        /** Delete a workorder */
        public static final EventTypeRegistration WORKORDER_DELETE = EventTypeRegistration.write("WORKORDER_DELETE",
                        "Delete a workorder by ID")
                        .apiVersion("1")
                        .build();

        /** Start work on a workorder */
        public static final EventTypeRegistration WORKORDER_START = EventTypeRegistration.write("WORKORDER_START",
                        "Start work on a workorder, transitioning to WORK_IN_PROGRESS")
                        .apiVersion("1")
                        .build();

        /** Approve a workorder with customer signature */
        public static final EventTypeRegistration WORKORDER_APPROVE = EventTypeRegistration
                        .approval("WORKORDER_APPROVE",
                                        "Approve workorder with customer signature capture")
                        .apiVersion("1")
                        .build();

        /** Complete a workorder */
        public static final EventTypeRegistration WORKORDER_COMPLETE = EventTypeRegistration.write("WORKORDER_COMPLETE",
                        "Complete a workorder, transitioning to COMPLETED status")
                        .apiVersion("1")
                        .build();

        /** Generate invoice draft from completed workorder */
        public static final EventTypeRegistration WORKORDER_INVOICE_GENERATE = EventTypeRegistration.write(
                        "WORKORDER_INVOICE_GENERATE",
                        "Generate invoice draft from completed workorder")
                        .apiVersion("1")
                        .build();

        /** Reopen a completed workorder for controlled edits */
        public static final EventTypeRegistration WORKORDER_REOPEN = EventTypeRegistration.write("WORKORDER_REOPEN",
                        "Reopen a completed workorder with mandatory reason and audit")
                        .apiVersion("1")
                        .build();

        // ==================== TECHNICIAN ASSIGNMENT EVENTS (CAP:005 Story #161)
        // ====================

        /** Assign a technician to a workorder */
        public static final EventTypeRegistration WORKORDER_TECHNICIAN_ASSIGN = EventTypeRegistration
                        .write("WORKORDER_TECHNICIAN_ASSIGN",
                                        "Assign a technician to a workorder")
                        .apiVersion("1")
                        .build();

        /** Reassign a workorder to a different technician */
        public static final EventTypeRegistration WORKORDER_TECHNICIAN_REASSIGN = EventTypeRegistration
                        .write("WORKORDER_TECHNICIAN_REASSIGN",
                                        "Reassign a workorder to a different technician")
                        .apiVersion("1")
                        .build();

        // ==================== LABOR TRACKING EVENTS (CAP:005 Story #159)
        // ====================

        /** Start a labor session on a workorder service */
        public static final EventTypeRegistration WORKORDER_LABOR_START = EventTypeRegistration
                        .write("WORKORDER_LABOR_START",
                                        "Start labor session on a workorder service")
                        .apiVersion("1")
                        .build();

        /** Stop a labor session */
        public static final EventTypeRegistration WORKORDER_LABOR_STOP = EventTypeRegistration
                        .write("WORKORDER_LABOR_STOP",
                                        "Stop an active labor session")
                        .apiVersion("1")
                        .build();

        /** Adjust labor hours manually */
        public static final EventTypeRegistration WORKORDER_LABOR_ADJUST = EventTypeRegistration
                        .write("WORKORDER_LABOR_ADJUST",
                                        "Manually adjust labor hours with reason")
                        .apiVersion("1")
                        .build();

        // ==================== ESTIMATE EVENTS ====================

        /** List all estimates */
        public static final EventTypeRegistration WORKORDER_ESTIMATE_LIST = EventTypeRegistration
                        .search("WORKORDER_ESTIMATE_LIST",
                                        "Retrieve list of all estimates")
                        .apiVersion("1")
                        .build();

        /** Search estimates by customer */
        public static final EventTypeRegistration WORKORDER_ESTIMATE_SEARCH_BY_CUSTOMER = EventTypeRegistration
                        .search("WORKORDER_ESTIMATE_SEARCH_BY_CUSTOMER",
                                        "Search estimates by customer ID")
                        .apiVersion("1")
                        .build();

        /** Search estimates by shop */
        public static final EventTypeRegistration WORKORDER_ESTIMATE_SEARCH_BY_SHOP = EventTypeRegistration
                        .search("WORKORDER_ESTIMATE_SEARCH_BY_SHOP",
                                        "Search estimates by shop/location ID")
                        .apiVersion("1")
                        .build();

        /** Search estimates by location */
        public static final EventTypeRegistration WORKORDER_ESTIMATE_SEARCH_BY_LOCATION = EventTypeRegistration
                        .search("WORKORDER_ESTIMATE_SEARCH_BY_LOCATION",
                                        "Search estimates by location ID")
                        .apiVersion("1")
                        .build();

        /**
         * Paginated search for estimates by optional customer and/or vehicle filter
         * (Story #15)
         */
        public static final EventTypeRegistration WORKORDER_ESTIMATE_SEARCH = EventTypeRegistration
                        .search("WORKORDER_ESTIMATE_SEARCH",
                                        "Paginated search for estimates by customer and/or vehicle")
                        .apiVersion("1")
                        .build();

        /** Create a new estimate */
        public static final EventTypeRegistration WORKORDER_ESTIMATE_CREATE = EventTypeRegistration
                        .write("WORKORDER_ESTIMATE_CREATE",
                                        "Create a new draft estimate")
                        .apiVersion("1")
                        .build();

        /** Create draft estimate from appointment */
        public static final EventTypeRegistration WORKORDER_ESTIMATE_CREATE_FROM_APPOINTMENT = EventTypeRegistration
                        .write("WORKORDER_ESTIMATE_CREATE_FROM_APPOINTMENT",
                                        "Create draft estimate from appointment")
                        .apiVersion("1")
                        .build();

        /** Decline an estimate */
        public static final EventTypeRegistration WORKORDER_ESTIMATE_DECLINE = EventTypeRegistration
                        .write("WORKORDER_ESTIMATE_DECLINE",
                                        "Transition estimate to declined state")
                        .apiVersion("1")
                        .build();

        /** Reopen a declined estimate */
        public static final EventTypeRegistration WORKORDER_ESTIMATE_REOPEN = EventTypeRegistration
                        .write("WORKORDER_ESTIMATE_REOPEN",
                                        "Reopen a declined estimate to DRAFT state")
                        .apiVersion("1")
                        .build();

        /** Approve an estimate with customer signature */
        public static final EventTypeRegistration WORKORDER_ESTIMATE_APPROVE = EventTypeRegistration
                        .approval("WORKORDER_ESTIMATE_APPROVE",
                                        "Approve estimate with customer signature capture")
                        .apiVersion("1")
                        .build();

        /** Promote approved estimate to workorder (CAP:004 Story #26) */
        public static final EventTypeRegistration WORKORDER_ESTIMATE_PROMOTE = EventTypeRegistration
                        .write("WORKORDER_ESTIMATE_PROMOTE",
                                        "Promote an approved estimate to a workorder")
                        .apiVersion("1")
                        .build();

        /** Submit estimate for customer approval (CAP:004 Issue #162) */
        public static final EventTypeRegistration WORKORDER_ESTIMATE_SUBMIT = EventTypeRegistration
                        .write("WORKORDER_ESTIMATE_SUBMIT",
                                        "Submit estimate for customer approval, creating immutable snapshot and transitioning to PENDING_APPROVAL")
                        .apiVersion("1")
                        .build();

        /** Delete an estimate */
        public static final EventTypeRegistration WORKORDER_ESTIMATE_DELETE = EventTypeRegistration
                        .write("WORKORDER_ESTIMATE_DELETE",
                                        "Delete an estimate by ID")
                        .apiVersion("1")
                        .build();

        // ==================== CHANGE REQUEST EVENTS ====================

        /** Create a change request */
        public static final EventTypeRegistration WORKORDER_CHANGE_REQUEST_CREATE = EventTypeRegistration
                        .write("WORKORDER_CHANGE_REQUEST_CREATE",
                                        "Technician creates request for additional work")
                        .apiVersion("1")
                        .build();

        /** Approve a change request */
        public static final EventTypeRegistration WORKORDER_CHANGE_REQUEST_APPROVE = EventTypeRegistration
                        .approval("WORKORDER_CHANGE_REQUEST_APPROVE",
                                        "Service Advisor approves change request")
                        .apiVersion("1")
                        .build();

        /** Decline a change request */
        public static final EventTypeRegistration WORKORDER_CHANGE_REQUEST_DECLINE = EventTypeRegistration
                        .approval("WORKORDER_CHANGE_REQUEST_DECLINE",
                                        "Service Advisor declines change request")
                        .apiVersion("1")
                        .build();

        /** Record customer denial acknowledgment */
        public static final EventTypeRegistration WORKORDER_CHANGE_REQUEST_DENIAL_ACKNOWLEDGE = EventTypeRegistration
                        .write("WORKORDER_CHANGE_REQUEST_DENIAL_ACKNOWLEDGE",
                                        "Customer acknowledges denial of emergency/safety item")
                        .apiVersion("1")
                        .build();

        /** Apply emergency override */
        public static final EventTypeRegistration WORKORDER_CHANGE_REQUEST_EMERGENCY_OVERRIDE = EventTypeRegistration
                        .approval("WORKORDER_CHANGE_REQUEST_EMERGENCY_OVERRIDE",
                                        "Manager applies emergency override to approve change request")
                        .apiVersion("1")
                        .build();

        /** List change requests for a workorder */
        public static final EventTypeRegistration WORKORDER_CHANGE_REQUEST_LIST = EventTypeRegistration
                        .search("WORKORDER_CHANGE_REQUEST_LIST",
                                        "List all change requests for a workorder")
                        .apiVersion("1")
                        .build();

        // ==================== APPROVAL CONFIGURATION EVENTS ====================

        /** List all approval configurations */
        public static final EventTypeRegistration WORKORDER_APPROVAL_CONFIG_LIST = EventTypeRegistration
                        .search("WORKORDER_APPROVAL_CONFIG_LIST",
                                        "List all approval configurations")
                        .apiVersion("1")
                        .build();

        /** Create an approval configuration */
        public static final EventTypeRegistration WORKORDER_APPROVAL_CONFIG_CREATE = EventTypeRegistration
                        .write("WORKORDER_APPROVAL_CONFIG_CREATE",
                                        "Create a new approval configuration")
                        .apiVersion("1")
                        .build();

        /** Update an approval configuration */
        public static final EventTypeRegistration WORKORDER_APPROVAL_CONFIG_UPDATE = EventTypeRegistration
                        .write("WORKORDER_APPROVAL_CONFIG_UPDATE",
                                        "Update an existing approval configuration")
                        .apiVersion("1")
                        .build();

        /** Delete an approval configuration */
        public static final EventTypeRegistration WORKORDER_APPROVAL_CONFIG_DELETE = EventTypeRegistration
                        .write("WORKORDER_APPROVAL_CONFIG_DELETE",
                                        "Delete an approval configuration")
                        .apiVersion("1")
                        .build();

        // ==================== ESTIMATE ITEM MANAGEMENT EVENTS (CAP:002 Stories #14,
        // #15, #17) ====================

        /** Add a line item to an estimate */
        public static final EventTypeRegistration ESTIMATE_ITEM_ADD = EventTypeRegistration
                        .write("ESTIMATE_ITEM_ADD",
                                        "Add a part or labor line item to a draft estimate")
                        .apiVersion("1")
                        .build();

        /** Update an existing line item on an estimate */
        public static final EventTypeRegistration ESTIMATE_ITEM_UPDATE = EventTypeRegistration
                        .write("ESTIMATE_ITEM_UPDATE",
                                        "Update a line item on a draft estimate")
                        .apiVersion("1")
                        .build();

        /** Remove a line item from an estimate */
        public static final EventTypeRegistration ESTIMATE_ITEM_DELETE = EventTypeRegistration
                        .write("ESTIMATE_ITEM_DELETE",
                                        "Remove a line item from a draft estimate")
                        .apiVersion("1")
                        .build();

        // ==================== ESTIMATE CALCULATION EVENTS (CAP:002 Story #16)
        // ====================

        /** Calculate taxes and totals for an estimate */
        public static final EventTypeRegistration ESTIMATE_CALCULATE = EventTypeRegistration
                        .write("ESTIMATE_CALCULATE",
                                        "Calculate subtotal, tax, and total for an estimate based on line items")
                        .apiVersion("1")
                        .build();

        // ==================== ESTIMATE SUMMARY EVENTS (CAP:002 Story #18)
        // ====================

        /** View customer-facing estimate summary */
        public static final EventTypeRegistration ESTIMATE_SUMMARY_VIEW = EventTypeRegistration
                        .fastRead("ESTIMATE_SUMMARY_VIEW",
                                        "Retrieve customer-facing summary of estimate with grouped line items")
                        .apiVersion("1")
                        .build();

        /** Generate PDF document for an estimate */
        public static final EventTypeRegistration ESTIMATE_PDF_GENERATE = EventTypeRegistration
                        .search("ESTIMATE_PDF_GENERATE",
                                        "Generate PDF document for estimate via pos-documents service")
                        .apiVersion("1")
                        .build();

        /** Create a historical snapshot of an estimate */
        public static final EventTypeRegistration ESTIMATE_SNAPSHOT_CREATE = EventTypeRegistration
                        .write("ESTIMATE_SNAPSHOT_CREATE",
                                        "Capture immutable snapshot of estimate state for audit trail")
                        .apiVersion("1")
                        .build();

        // ==================== PARTS USAGE EVENTS (CAP:005 Story #158)
        // ====================

        /** Issue parts to a workorder */
        public static final EventTypeRegistration WORKORDER_PART_ISSUE = EventTypeRegistration
                        .write("WORKORDER_PART_ISSUE",
                                        "Issue parts from inventory to a workorder")
                        .apiVersion("1")
                        .build();

        /** Consume parts on a workorder */
        public static final EventTypeRegistration WORKORDER_PART_CONSUME = EventTypeRegistration
                        .write("WORKORDER_PART_CONSUME",
                                        "Record actual consumption of parts on a workorder")
                        .apiVersion("1")
                        .build();

        /** Return unused parts to inventory */
        public static final EventTypeRegistration WORKORDER_PART_RETURN = EventTypeRegistration
                        .write("WORKORDER_PART_RETURN",
                                        "Return unused parts to inventory after partial consumption")
                        .apiVersion("1")
                        .build();

        // ==================== PARTS ADJUSTMENT EVENTS (CAP:005 Story #157)
        // ====================

        /** Substitute one part for another */
        public static final EventTypeRegistration WORKORDER_PART_SUBSTITUTE = EventTypeRegistration
                        .write("WORKORDER_PART_SUBSTITUTE",
                                        "Substitute one part for another (preserves original for history)")
                        .apiVersion("1")
                        .build();

        /** Return unused quantity beyond normal return flow */
        public static final EventTypeRegistration WORKORDER_PART_RETURN_UNUSED = EventTypeRegistration
                        .write("WORKORDER_PART_RETURN_UNUSED",
                                        "Return unused part quantity beyond normal return flow")
                        .apiVersion("1")
                        .build();

        /** Correct part quantity (administrative correction) */
        public static final EventTypeRegistration WORKORDER_PART_CORRECT = EventTypeRegistration
                        .write("WORKORDER_PART_CORRECT",
                                        "Administrative correction for part quantity data entry errors")
                        .apiVersion("1")
                        .build();

        // ==================== WIP DASHBOARD EVENTS (CAP-248 Story #14)
        // ====================

        /** List WIP workorders for a location */
        public static final EventTypeRegistration WORKORDER_WIP_LIST = EventTypeRegistration
                        .search("WORKORDER_WIP_LIST",
                                        "Retrieve work-in-progress workorders for a location")
                        .apiVersion("1")
                        .build();

        /** Retrieve full WIP detail for a given workorder */
        public static final EventTypeRegistration WORKORDER_WIP_VIEW = EventTypeRegistration
                        .fastRead("WORKORDER_WIP_VIEW",
                                        "Retrieve full WIP detail for a given workorder")
                        .apiVersion("1")
                        .build();

        // ==================== ALL EVENT TYPES ====================

        /** All event types for registration at startup */
        public static final List<EventTypeRegistration> ALL_EVENT_TYPES = List.of(
                        // Workorder events
                        WORKORDER_LIST,
                        WORKORDER_CREATE,
                        WORKORDER_DELETE,
                        WORKORDER_START,
                        WORKORDER_APPROVE,
                        WORKORDER_COMPLETE,
                        WORKORDER_INVOICE_GENERATE,
                        WORKORDER_REOPEN,
                        // Technician assignment events (CAP:005 Story #161)
                        WORKORDER_TECHNICIAN_ASSIGN,
                        WORKORDER_TECHNICIAN_REASSIGN,
                        // Labor tracking events (CAP:005 Story #159)
                        WORKORDER_LABOR_START,
                        WORKORDER_LABOR_STOP,
                        WORKORDER_LABOR_ADJUST,
                        // Estimate events
                        WORKORDER_ESTIMATE_LIST,
                        WORKORDER_ESTIMATE_SEARCH_BY_CUSTOMER,
                        WORKORDER_ESTIMATE_SEARCH_BY_SHOP,
                        WORKORDER_ESTIMATE_SEARCH_BY_LOCATION,
                        WORKORDER_ESTIMATE_SEARCH,
                        WORKORDER_ESTIMATE_CREATE,
                        WORKORDER_ESTIMATE_CREATE_FROM_APPOINTMENT,
                        WORKORDER_ESTIMATE_DECLINE,
                        WORKORDER_ESTIMATE_REOPEN,
                        WORKORDER_ESTIMATE_APPROVE,
                        WORKORDER_ESTIMATE_PROMOTE,
                        WORKORDER_ESTIMATE_SUBMIT,
                        WORKORDER_ESTIMATE_DELETE,
                        // Estimate item management (CAP:002)
                        ESTIMATE_ITEM_ADD,
                        ESTIMATE_ITEM_UPDATE,
                        ESTIMATE_ITEM_DELETE,
                        // Estimate calculation (CAP:002)
                        ESTIMATE_CALCULATE,
                        // Estimate summary (CAP:002)
                        ESTIMATE_SUMMARY_VIEW,
                        ESTIMATE_PDF_GENERATE,
                        ESTIMATE_SNAPSHOT_CREATE,
                        // Parts usage events (CAP:005 Story #158)
                        WORKORDER_PART_ISSUE,
                        WORKORDER_PART_CONSUME,
                        WORKORDER_PART_RETURN,
                        // Parts adjustment events (CAP:005 Story #157)
                        WORKORDER_PART_SUBSTITUTE,
                        WORKORDER_PART_RETURN_UNUSED,
                        WORKORDER_PART_CORRECT,
                        // Change request events
                        WORKORDER_CHANGE_REQUEST_CREATE,
                        WORKORDER_CHANGE_REQUEST_APPROVE,
                        WORKORDER_CHANGE_REQUEST_DECLINE,
                        WORKORDER_CHANGE_REQUEST_DENIAL_ACKNOWLEDGE,
                        WORKORDER_CHANGE_REQUEST_EMERGENCY_OVERRIDE,
                        WORKORDER_CHANGE_REQUEST_LIST,
                        // Approval configuration events
                        WORKORDER_APPROVAL_CONFIG_LIST,
                        WORKORDER_APPROVAL_CONFIG_CREATE,
                        WORKORDER_APPROVAL_CONFIG_UPDATE,
                        WORKORDER_APPROVAL_CONFIG_DELETE,
                        // WIP dashboard events (CAP-248 Story #14)
                        WORKORDER_WIP_LIST,
                        WORKORDER_WIP_VIEW);
}
