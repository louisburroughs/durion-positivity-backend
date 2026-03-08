package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.UserAuthContext;
import com.positivity.securityservice.internal.dto.UserDto;
import com.positivity.securityservice.internal.dto.UserUpdateRequest;
import com.positivity.securityservice.service.JwtService;
import com.positivity.securityservice.service.UserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Tag(name = "User API", description = "Endpoints for user management and authentication")
@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    @Operation(summary = "Create a new user", description = "Creates a new user with username, password, and roles.")
    @ApiResponse(responseCode = "201", description = "User created successfully.")
    @EmitEvent(id = "SECURITY_USER_CREATE", apiVersion = "1")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<UserDto> createUser(@RequestBody Map<String, Object> payload) {
        String username = (String) payload.get("username");
        String password = (String) payload.get("password");
        List<?> rolesList = (List<?>) payload.get("roles");
        Set<String> roles = rolesList.stream()
                .map(Object::toString)
                .collect(Collectors.toSet());
        UserDto user = userService.createUser(username, password, roles);
        return ResponseEntity.status(201).body(user);
    }

    @Operation(summary = "User login", description = "Authenticates a user and returns a JWT token.")
    @EmitEvent(id = "SECURITY_USER_LOGIN", apiVersion = "1")
    @PreAuthorize("permitAll()")
    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody Map<String, String> payload) {
        String username = payload.get("username");
        String password = payload.get("password");
        Optional<UserAuthContext> userOpt = userService.getUserByUsername(username);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401)
                    .body(Map.of("code", "INVALID_CREDENTIALS", "message", "Invalid credentials"));
        }
        UserAuthContext user = userOpt.get();
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            return ResponseEntity.status(401)
                    .body(Map.of("code", "INVALID_CREDENTIALS", "message", "Invalid credentials"));
        }
        if (user.getId() == null) {
            throw new IllegalStateException("User exists but id is missing for username: " + username);
        }
        Set<String> roles = user.getRoles();
        String token = jwtService.generateToken(username, user.getId(), roles);
        return ResponseEntity.ok(Map.of("token", token));
    }

    @Operation(summary = "Get all users", description = "Retrieve a list of all users.")
    @ApiResponse(responseCode = "200", description = "List of users returned successfully.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public List<UserDto> getAllUsers() {
        return userService.getAllUsers();
    }

    @Operation(summary = "Get user by ID", description = "Retrieve a user by their unique ID.")
    @ApiResponse(responseCode = "200", description = "User found and returned.")
    @ApiResponse(responseCode = "404", description = "User not found.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{id}")
    public ResponseEntity<UserDto> getUserById(
            @Parameter(description = "ID of the user to retrieve", example = "123e4567-e89b-12d3-a456-426614174000") @PathVariable UUID id) {
        Optional<UserDto> user = userService.getUserById(id);
        return user.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Update an existing user", description = "Update the details of an existing user.")
    @ApiResponse(responseCode = "200", description = "User updated successfully.")
    @ApiResponse(responseCode = "404", description = "User not found.")
    @EmitEvent(id = "SECURITY_USER_UPDATE", apiVersion = "1")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public ResponseEntity<UserDto> updateUser(
            @Parameter(description = "ID of the user to update", example = "123e4567-e89b-12d3-a456-426614174000") @PathVariable UUID id,
            @Parameter(description = "Updated user object") @RequestBody UserUpdateRequest user) {
        UserDto updatedUser = userService.updateUser(id, user);
        return ResponseEntity.ok(updatedUser);
    }

    @Operation(summary = "Delete a user", description = "Delete a user by their unique ID.")
    @ApiResponse(responseCode = "204", description = "User deleted successfully.")
    @ApiResponse(responseCode = "404", description = "User not found.")
    @EmitEvent(id = "SECURITY_USER_DELETE", apiVersion = "1")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(
            @Parameter(description = "ID of the user to delete", example = "123e4567-e89b-12d3-a456-426614174000") @PathVariable UUID id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    @EmitEvent(id = "SECURITY_USER_ASSIGN_ROLES", apiVersion = "1")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{username}/roles")
    public ResponseEntity<UserDto> assignRoles(@PathVariable String username,
            @RequestBody Map<String, Object> payload) {
        List<?> rolesList = (List<?>) payload.get("roles");
        Set<String> roles = rolesList.stream()
                .map(Object::toString)
                .collect(Collectors.toSet());
        UserDto user = userService.assignRoles(username, roles);
        return ResponseEntity.ok(user);
    }
}
