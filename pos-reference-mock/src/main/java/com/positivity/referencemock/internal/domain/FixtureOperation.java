package com.positivity.referencemock.internal.domain;

/**
 * One vendor operation in the fixture catalog.
 *
 * @param providerOperationCode vendor code, {@code MG-<DURION-CODE>} for mapped operations
 * @param name human-readable operation name (search target of the operations endpoint)
 * @param category vendor category: REPAIR, MAINTENANCE, DIAGNOSTIC or TIRE_SERVICE
 */
public record FixtureOperation(String providerOperationCode, String name, String category) {}
