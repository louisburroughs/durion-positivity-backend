package com.positivity.bulkloader.internal.domain;

import lombok.Data;

/**
 * One security role to provision, with its MCP persona metadata (#1613, D8).
 *
 * <p>Roles move out of Flyway because they are not schema: they bundle permission grants and key an
 * assistant persona, and treating role rows as migrations is what produced the drift this issue
 * fixes — a role added to SQL was invisible to anything that did not also get a Java edit.
 *
 * <p>Grants are deliberately not on this record. They load separately, as
 * {@link RolePermissionLoaderRecord}, so a role can be created before the permissions it will hold
 * are registered — permissions are registered code-first by each module at startup, so the two
 * cannot be assumed present at the same moment.
 */
@Data
public class RoleLoaderRecord {

    /** Stored unprefixed and upper-case; the ROLE_ authority prefix is applied by the gateway. */
    private String name;

    private String description;

    /** Persona slot: who the caller is. Derived from the name downstream when blank. */
    private String personaTitle;

    /** Persona slot: what they work on. Derived from the description downstream when blank. */
    private String personaFocus;

    /** Persona slot: how to speak to them. Defaulted downstream when blank. */
    private String personaTone;

    /** MCP persona resolution priority, lowest first. Blank leaves the role unranked. */
    private String mcpPersonaRank;

    /** Whether the role participates in MCP persona resolution. Blank means true. */
    private String mcpPersonaEligible;
}
