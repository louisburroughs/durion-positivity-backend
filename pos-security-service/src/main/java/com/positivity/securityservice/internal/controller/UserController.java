package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.UserDto;
import com.positivity.securityservice.internal.dto.UserUpdateRequest;
import com.positivity.securityservice.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "User API", description = "Endpoints for user management and authentication")
@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @Operation(operationId = "createUser", summary = "Create a User With Roles", description = """
                    Creates a user account with a username, a hashed password, and a set of directly attached \
                    roles.
                    Use this tool for operator provisioning of accounts; do not use selfRegisterUser, the anonymous \
                    customer flow that fixes the role to SELF_SERVICE_CUSTOMER and runs identity resolution first.
                    Preconditions: the caller must hold security:user:create, the username must be unused, and \
                    every named role must already exist.
                    Required inputs: username, password, and roles, a non-empty array of existing role names.
                    Emits a SECURITY_USER_CREATE event; the password is hashed before storage.
                    Returns 400 when the username already exists or a named role is not found.
                    """)
    @ApiResponse(responseCode = "201", description = "User created successfully.")
    @EmitEvent(id = "SECURITY_USER_CREATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:user:create"})
    @PreAuthorize("hasAuthority('security:user:create')")
    @PostMapping
    public ResponseEntity<UserDto> createUser(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Username, password, and role names of the account to create.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "New user", value = """
                                                                    {"username":"jane.doe",
                                                                     "password":"Sup3rS3cret!",
                                                                     "roles":["SHOP_MGR"]}
                                                                    """)))
                    @RequestBody
                    Map<String, Object> payload) {
        String username = (String) payload.get("username");
        String password = (String) payload.get("password");
        List<?> rolesList = (List<?>) payload.get("roles");
        Set<String> roles = rolesList.stream().map(Object::toString).collect(Collectors.toSet());
        UserDto user = userService.createUser(username, password, roles);
        return ResponseEntity.status(201).body(user);
    }

    @Operation(operationId = "listUsers", summary = "List All User Accounts", description = """
                    Returns every user account with its id, username, effective role names, and linked personId.
                    Use this tool to enumerate accounts; use getUserById instead for one known user.
                    Preconditions: the caller must hold security:user:view.
                    Required inputs: none; there are no filters or paging parameters, so the full user table is \
                    returned.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty list when no users exist; there are no business error conditions.
                    """)
    @ApiResponse(responseCode = "200", description = "List of users returned successfully.")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:user:view"})
    @PreAuthorize("hasAuthority('security:user:view')")
    @GetMapping
    public List<UserDto> getAllUsers() {
        return userService.getAllUsers();
    }

    @Operation(operationId = "getUserById", summary = "Get a User Account by Id", description = """
                    Returns a single user account by UUID, including effective role names merged from direct roles \
                    and currently active role assignments.
                    Use this tool when the user id is known; use listUsers instead to browse accounts, and \
                    getUserAccountState for administrative lock and expiry flags.
                    Preconditions: the caller must hold security:user:view and the user must exist.
                    Required inputs: id (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no user exists for the supplied id.
                    """)
    @ApiResponse(responseCode = "200", description = "User found and returned.")
    @ApiResponse(responseCode = "404", description = "User not found.")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:user:view"})
    @PreAuthorize("hasAuthority('security:user:view')")
    @GetMapping("/{id}")
    public ResponseEntity<UserDto> getUserById(
            @Parameter(description = "ID of the user to retrieve", example = "123e4567-e89b-12d3-a456-426614174000")
                    @PathVariable
                    UUID id) {
        Optional<UserDto> user = userService.getUserById(id);
        return user.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(operationId = "updateUser", summary = "Partially Update a User Account", description = """
                    Applies a partial update to a user account: username, password, and the direct role set are \
                    each replaced only when supplied.
                    Use this tool to change account fields; do not use assignUserRolesByUsername, which only \
                    replaces roles, and do not use the account-state endpoints such as disableUserAccount, which \
                    flip administrative flags.
                    Preconditions: the caller must hold security:user:edit, the user must exist, and any named role \
                    must already exist.
                    Required inputs: id (UUID) as a path parameter; username, password, and roles are all optional, \
                    and omitted or blank fields are left unchanged.
                    Emits a SECURITY_USER_UPDATE event; a supplied password is re-hashed before storage.
                    Returns 400 with INVALID_REQUEST when the user or a named role cannot be found; the miss \
                    surfaces as 400 rather than 404.
                    """)
    @ApiResponse(responseCode = "200", description = "User updated successfully.")
    @ApiResponse(
            responseCode = "400",
            description = "User or named role not found (INVALID_REQUEST); the miss surfaces as 400, not 404.")
    @EmitEvent(id = "SECURITY_USER_UPDATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:user:edit"})
    @PreAuthorize("hasAuthority('security:user:edit')")
    @PutMapping("/{id}")
    public ResponseEntity<UserDto> updateUser(
            @Parameter(description = "ID of the user to update", example = "123e4567-e89b-12d3-a456-426614174000")
                    @PathVariable
                    UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The account fields to change; omitted fields are left untouched.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Rename and reset roles", value = """
                                                                    {"username":"jane.doe","roles":["SHOP_MGR"]}
                                                                    """)))
                    @RequestBody
                    UserUpdateRequest user) {
        UserDto updatedUser = userService.updateUser(id, user);
        return ResponseEntity.ok(updatedUser);
    }

    @Operation(operationId = "deleteUser", summary = "Delete a User Account", description = """
                    Deletes a user account and queues removal of its user-person link so downstream projections \
                    follow the account out.
                    Use this tool to remove an account permanently; do not use disableUserAccount, which blocks \
                    sign-in reversibly and keeps the record.
                    Preconditions: the caller must hold security:user:delete; deleting an id that does not exist is \
                    a silent no-op.
                    Required inputs: id (UUID) as a path parameter.
                    Emits a SECURITY_USER_DELETE event and sends a UserPersonLinkRemoveRequested command to the \
                    people-contact domain in the same transaction.
                    Returns 204 in all cases, including when the user was already absent.
                    """)
    @ApiResponse(responseCode = "204", description = "User deleted successfully.")
    @ApiResponse(responseCode = "404", description = "User not found.")
    @EmitEvent(id = "SECURITY_USER_DELETE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:user:delete"})
    @PreAuthorize("hasAuthority('security:user:delete')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(
            @Parameter(description = "ID of the user to delete", example = "123e4567-e89b-12d3-a456-426614174000")
                    @PathVariable
                    UUID id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            operationId = "assignUserRolesByUsername",
            summary = "Replace a User's Direct Role Set",
            description = """
                    Replaces a user's directly attached role set with the supplied role names, looking the user up \
                    by username.
                    Use this tool for wholesale role replacement by username; do not use assignUserRole, which adds \
                    a single scoped role assignment by UUID without touching the direct set.
                    Preconditions: the caller must hold security:role:assign, the username must resolve to a user, \
                    and every named role must exist.
                    Required inputs: username as a path parameter and roles, an array of existing role names, in \
                    the body; the set replaces all current direct roles.
                    Emits a SECURITY_USER_ASSIGN_ROLES event.
                    Returns 400 with INVALID_REQUEST when the user or a named role cannot be found; the miss \
                    surfaces as 400 rather than 404.
                    """)
    @ApiResponse(responseCode = "200", description = "User roles updated successfully.")
    @ApiResponse(
            responseCode = "400",
            description = "User or named role not found (INVALID_REQUEST); the miss surfaces as 400, not 404.")
    @EmitEvent(id = "SECURITY_USER_ASSIGN_ROLES", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:assign"})
    @PreAuthorize("hasAuthority('security:role:assign')")
    @PutMapping("/{username}/roles")
    public ResponseEntity<UserDto> assignRoles(
            @Parameter(description = "Username of the user whose roles are being assigned") @PathVariable
                    String username,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The replacement set of role names for the user.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Replace roles", value = """
                                                                    {"roles":["SHOP_MGR","ACCOUNTING_CLERK"]}
                                                                    """)))
                    @RequestBody
                    Map<String, Object> payload) {
        List<?> rolesList = (List<?>) payload.get("roles");
        Set<String> roles = rolesList.stream().map(Object::toString).collect(Collectors.toSet());
        UserDto user = userService.assignRoles(username, roles);
        return ResponseEntity.ok(user);
    }
}
