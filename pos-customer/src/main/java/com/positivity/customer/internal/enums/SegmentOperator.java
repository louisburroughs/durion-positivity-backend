package com.positivity.customer.internal.enums;

/** Comparison operators a segment predicate may use (Story #1137). */
public enum SegmentOperator {
    EQUALS,
    NOT_EQUALS,
    IN,
    NOT_IN,
    GREATER_THAN,
    GREATER_THAN_OR_EQUAL,
    LESS_THAN,
    LESS_THAN_OR_EQUAL,
    IS_TRUE,
    IS_FALSE,
    IS_NULL,
    IS_NOT_NULL,
    CONTAINS_ANY,
    CONTAINS_ALL
}
