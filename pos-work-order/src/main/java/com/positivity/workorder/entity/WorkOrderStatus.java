package com.positivity.workorder.entity;

public enum WorkOrderStatus {
    DRAFT,
    IN_PROGRESS,
    AWAITING_PARTS,
    AWAITING_APPROVAL,
    READY_FOR_PICKUP,
    COMPLETED,
    CANCELLED
}
