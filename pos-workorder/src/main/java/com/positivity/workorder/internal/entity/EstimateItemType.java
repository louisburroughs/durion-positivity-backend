package com.positivity.workorder.internal.entity;

/**
 * Type of line item on an estimate.
 */
public enum EstimateItemType {
    /**
     * Part or product item (physical goods)
     */
    PART,

    /**
     * Labor or service item (work performed)
     */
    LABOR
}
