package com.positivity.inboundmail.internal.enums;

/** Where a received message stands at the edge (Part B moves it through these). */
public enum MessageStatus {
    RECEIVED,
    REJECTED,
    STAGED,
    COLLECTED,
    EXPIRED
}
