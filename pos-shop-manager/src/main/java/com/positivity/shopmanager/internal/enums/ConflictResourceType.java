package com.positivity.shopmanager.internal.enums;

/**
 * The kind of resource a conflict rule constrains (DECISION-SHOPMGMT-002's {@code
 * conflict_resource_type}). Stored as a varchar + CHECK like every other enum in this module's
 * schema; the record's {@code CREATE TYPE} was never shipped and is not started here.
 */
public enum ConflictResourceType {
    BAY,
    MECHANIC,
    CAPACITY,
    HOURS,
    SKILL
}
