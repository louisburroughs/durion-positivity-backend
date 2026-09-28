package com.positivity.accounting.internal.bankrec.enums;

/** Who proposed a reconciliation match (SPEC §3.4). A {@code RULE} match is never accepted by the system (M6). */
public enum MatchOrigin {
    USER,
    RULE
}
