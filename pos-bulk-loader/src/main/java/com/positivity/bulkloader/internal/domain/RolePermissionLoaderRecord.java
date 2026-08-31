package com.positivity.bulkloader.internal.domain;

import lombok.Data;

/**
 * Permission grants for one role (#1613, D8 constraint 2).
 *
 * <p>A separate load from {@link RoleLoaderRecord} rather than grants carried inline, for two
 * reasons. Ordering: permissions are registered code-first by each module's
 * {@code {Module}PermissionRegistry} at startup, so the set a role can be granted is not knowable
 * when the role itself is created. And environment shape: bulk loads are per-environment operator
 * actions, so which grants an environment gets is a separate decision from which roles exist.
 *
 * <p>Loading grants after the platform is up is a correctness gain over the SQL seed, which ran
 * during pos-security-service boot — before the other modules had registered anything — and so was
 * a snapshot that drifted from the registries it mirrored.
 */
@Data
public class RolePermissionLoaderRecord {

    /** Role to grant to; must already exist. */
    private String roleName;

    /** Semicolon-separated permission names, e.g. {@code crm:party:view;order:shipment:cancel}. */
    private String permissions;
}
