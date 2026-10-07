package com.positivity.accounting.internal.enums;

/** A bank deposit of drawer cash (CAP:550 S18, #2514): {@code RECORDED → REVERSED}, terminal (ADR-0047). */
public enum DepositStatus {
    /** Its entry stands. */
    RECORDED,
    /** Its entry was reversed; its sessions are waiting to be deposited again. */
    REVERSED
}
