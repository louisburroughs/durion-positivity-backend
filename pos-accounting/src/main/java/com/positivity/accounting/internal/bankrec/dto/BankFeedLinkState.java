package com.positivity.accounting.internal.bankrec.dto;

/** Whether a bank account is linked to a bank feed (SPEC §4.1; phase 2 links, so phase 1 is always NONE). */
public enum BankFeedLinkState {
    NONE,
    LINKED
}
