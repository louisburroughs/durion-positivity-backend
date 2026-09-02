package com.positivity.referencemock.internal.dto;

/**
 * Normalized vehicle key as supplied by the caller; any field may be {@code null} (absent request
 * parameter). Field vocabulary mirrors the pos-vehicle-fitment strings per plan §4.3.
 *
 * @param year model year string, or null
 * @param make vehicle make, or null
 * @param model vehicle model, or null
 * @param submodel vehicle submodel/trim, or null
 * @param engineCode engine code, or null
 */
public record VehicleQuery(String year, String make, String model, String submodel, String engineCode) {}
