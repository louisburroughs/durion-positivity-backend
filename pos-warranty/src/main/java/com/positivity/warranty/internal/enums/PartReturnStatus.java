package com.positivity.warranty.internal.enums;

/** RMA child lifecycle (PRD §3.8): AWAITING_PART → ON_HOLD → SHIPPED → RECEIVED_BY_VENDOR | SCRAPPED | CLOSED. */
public enum PartReturnStatus {
    AWAITING_PART,
    ON_HOLD,
    SHIPPED,
    RECEIVED_BY_VENDOR,
    SCRAPPED,
    CLOSED
}
