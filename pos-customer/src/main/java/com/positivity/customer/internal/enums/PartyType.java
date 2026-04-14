package com.positivity.customer.internal.enums;

/**
 * Enumeration of party types in the system.
 *
 * <p>
 * Defines the different types of parties that can exist:
 * <ul>
 * <li>PERSON - Individual customers</li>
 * <li>COMMERCIAL - Business/organization parties</li>
 * </ul>
 */
public enum PartyType {
    /**
     * Individual person or private customer.
     */
    PERSON,

    /**
     * Commercial organization or business entity.
     */
    COMMERCIAL,
    UNKNOWN
}
