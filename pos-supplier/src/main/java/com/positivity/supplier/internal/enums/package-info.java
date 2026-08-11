/**
 * Persistence-side enums of the ADR-0050 vendor profile model, mirroring the contract enums
 * in {@code com.positivity.supplier.service.model} value-for-value. Entities may not depend
 * on the {@code service} contract surface (platform rule, pos-archunit
 * {@code ArchitectureTests#entitiesShouldNotDependOnServices}); the service implementations
 * map between the two by name — mirroring the pos-warranty {@code internal.enums} convention.
 */
package com.positivity.supplier.internal.enums;
