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
    public static final EventTypeRegistration WORKORDER_APPROVE = EventTypeRegistration.approval("WORKORDER_APPROVE",
            "Approve workorder with customer signature capture")
            .apiVersion("1")
            .build();

    /** Complete a workorder */
    public static final EventTypeRegistration WORKORDER_COMPLETE = EventTypeRegistration.write("WORKORDER_COMPLETE",
            "Complete a workorder, transitioning to COMPLETED status")
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

    /** Create a new estimate */
    public static final EventTypeRegistration WORKORDER_ESTIMATE_CREATE = EventTypeRegistration
            .write("WORKORDER_ESTIMATE_CREATE",
                    "Create a new draft estimate")
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
            // Estimate events
            WORKORDER_ESTIMATE_LIST,
            WORKORDER_ESTIMATE_SEARCH_BY_CUSTOMER,
            WORKORDER_ESTIMATE_SEARCH_BY_SHOP,
            WORKORDER_ESTIMATE_SEARCH_BY_LOCATION,
            WORKORDER_ESTIMATE_CREATE,
            WORKORDER_ESTIMATE_DECLINE,
            WORKORDER_ESTIMATE_REOPEN,
            WORKORDER_ESTIMATE_APPROVE,
            WORKORDER_ESTIMATE_DELETE,
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
            WORKORDER_APPROVAL_CONFIG_DELETE);
}
