package com.positivity.peoplecontact.internal.enums;

/**
 * Type of a person contact point. Mirrors the customer-domain contact taxonomy
 * so contact data can be consolidated in pos-people-contact (ADR-0015 I2, ADR-0044 §6).
 */
public enum ContactPointType {
    EMAIL,
    PHONE_MOBILE,
    PHONE_HOME,
    PHONE_WORK
}
