package com.positivity.bulkloader.internal.enums;

public enum DomainType {
    CATALOG_PRODUCT,
    INVENTORY_STOCK_COUNT,
    LOCATION,
    CUSTOMER,
    COMMERCIAL_CUSTOMER,
    PERSON,
    BASE_PRICE,
    VEHICLE,
    VEHICLE_FITMENT,
    STORAGE_LOCATION,
    BAY,
    MOBILE_UNIT,
    STAFFING_ASSIGNMENT,
    PUTAWAY_RULE,
    CYCLE_COUNT_PLAN,
    SECURITY_ROLE,
    SECURITY_ROLE_PERMISSION,
    SECURITY_USER,
    USER_PERSON_LINK,
    PERSON_CREDENTIAL,
    CATALOG_SERVICE,
    SERVICE_LABOR_STANDARD,
    SERVICE_PACKAGE,
    SERVICE_PACKAGE_MEMBER,
    LABOR_RATE,
    LABOR_RATE_ADJUSTMENT,
    /**
     * Read-only placeholder for a job whose stored domain has since been removed from this enum
     * (MECHANIC_SKILL, retired by CAP-328). Only {@code DomainTypeConverter} produces it; a job can
     * never be created or launched for it.
     */
    RETIRED
}
