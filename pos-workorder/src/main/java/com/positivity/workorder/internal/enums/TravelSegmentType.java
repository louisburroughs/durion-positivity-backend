package com.positivity.workorder.internal.enums;

/**
 * Classifies the direction/purpose of a single travel leg for a mobile work assignment.
 */
public enum TravelSegmentType {
    DEPART_SHOP,
    ARRIVE_CUSTOMER_SITE,
    DEPART_CUSTOMER_SITE,
    ARRIVE_SHOP,
    TRAVEL_BETWEEN_SITES,
    DEADHEAD
}
