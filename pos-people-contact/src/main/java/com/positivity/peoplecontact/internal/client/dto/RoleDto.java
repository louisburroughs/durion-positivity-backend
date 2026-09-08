package com.positivity.peoplecontact.internal.client.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Wire shape of pos-security-service's {@code RoleDto}, returned by {@code GET /v1/roles}, and
 * the body this module hands back from {@code listAssignableRoles}.
 *
 * <p>The role {@code name} is the stable role code in this system (for example {@code SHOP_MGR}
 * or {@code TECHNICIAN}) — pos-security-service has no separate {@code code} field, and
 * {@code GET /v1/roles/by-name/{name}} resolves an assignment by exactly this value. {@link
 * #getCode()} is therefore a derived alias for {@link #getName()} rather than a field of its
 * own: as a standalone field it was never populated by any response and callers selecting a role
 * by {@code code} were reading null.
 *
 * <p>There is no {@code scopeType} here. Under ADR-0061 a role's location scope lives on the
 * role in pos-security-service and is resolved at token issuance against the person's pos-people
 * staffing assignment; it is not part of picking a role to assign. The role catalog is likewise
 * unfiltered — {@code GET /v1/roles} takes no parameters — so there is nothing to distinguish
 * "location roles" from "global roles" at this edge. An {@code active} flag was declared here
 * too and is not part of the downstream contract at all.
 *
 * <p>The permission set and MCP persona metadata pos-security-service also returns are absorbed
 * by {@code ignoreUnknown}: nothing in this module reads them.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Role definition as exposed by the security service")
public class RoleDto {

    @Schema(description = "Role identifier", example = "01960011-0000-7000-8000-000000000020", requiredMode = REQUIRED)
    private UUID id;

    @Schema(description = "Role name, which is also its stable code", example = "TECHNICIAN", requiredMode = REQUIRED)
    private String name;

    @Schema(
            description = "Description of the role",
            example = "Performs service work on vehicles",
            requiredMode = NOT_REQUIRED)
    private String description;

    /**
     * The stable role code, which in this system is the role name. Read-only projection of
     * {@link #getName()}; assignment requests round-trip this value back through
     * {@code GET /v1/roles/by-name/{name}}.
     */
    @Schema(description = "Stable role code, identical to the name", example = "TECHNICIAN", requiredMode = REQUIRED)
    public String getCode() {
        return name;
    }
}
