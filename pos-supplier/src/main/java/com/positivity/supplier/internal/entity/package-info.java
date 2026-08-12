/**
 * JPA entities of the vendor profile configuration model (ADR-0050): the profile itself plus
 * its three child collections — auth configs, commercial accounts, and endpoint bindings.
 *
 * <p>Identity follows ADR-0013/0027 (UUIDv7 via {@code @UUIDv7Id}); audit fields follow
 * ADR-0018/0024 ({@code AuditingEntityListener} with the module {@code JpaConfig} auditor).
 * Children reference their owning profile by bare {@code vendorProfileId} UUID column (DB FK
 * in {@code V2__supplier_vendor_profiles.sql}), mirroring the pos-warranty
 * {@code WarrantyPolicy → WarrantyProvider} convention. Package registered in the platform
 * {@code EntityStandardsArchitectureTest} enforcement list.
 */
package com.positivity.supplier.internal.entity;
