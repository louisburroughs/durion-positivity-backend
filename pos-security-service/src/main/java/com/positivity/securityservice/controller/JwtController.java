package com.positivity.securityservice.controller;

import com.positivity.securityservice.service.JwtService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

@Slf4j
@Tag(name = "JWT API", description = "Endpoints for JWT authentication and token management")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class JwtController {
    private final JwtService jwtService;

    @Operation(summary = "Authenticate user and issue JWT", description = "Authenticate with username and password to receive a JWT token.")
    @ApiResponse(responseCode = "200", description = "JWT token issued successfully.")
    @ApiResponse(responseCode = "401", description = "Invalid credentials.")
    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> generateToken(
            @RequestParam String subject,
            @RequestParam(required = false) Set<String> roles) {
        String token = jwtService.generateToken(subject, roles != null ? roles : Set.of());
        return ResponseEntity.ok(Map.of("token", token));
    }

    @PostMapping("/token-pair")
    public ResponseEntity<JwtService.TokenPair> generateTokenPair(
            @RequestParam String subject,
            @RequestParam(required = false) Set<String> roles) {
        JwtService.TokenPair tokenPair = jwtService.generateTokenPair(subject, roles != null ? roles : Set.of());
        return ResponseEntity.ok(tokenPair);
    }

    @PostMapping("/refresh")
    public ResponseEntity<JwtService.TokenPair> refreshAccessToken(@RequestParam String refreshToken) {
        try {
            JwtService.TokenPair tokenPair = jwtService.refreshAccessToken(refreshToken);
            return ResponseEntity.ok(tokenPair);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).build();
        }
    }

    @Operation(summary = "Validate JWT token", description = "Validate a JWT token and return its claims if valid.")
    @ApiResponse(responseCode = "200", description = "Token is valid.")
    @ApiResponse(responseCode = "401", description = "Token is invalid or expired.")
    @GetMapping("/validate")
    public ResponseEntity<Map<String, Boolean>> validateToken(@RequestParam String token) {
        boolean valid = jwtService.validateToken(token);
        return ResponseEntity.ok(Map.of("valid", valid));
    }

    @DeleteMapping("/delete")
    public ResponseEntity<Void> deleteToken(@RequestParam String token) {
        jwtService.deleteToken(token);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/roles")
    public ResponseEntity<Set<String>> getRoles(@RequestParam String token) {
        if (!jwtService.validateToken(token)) {
            return ResponseEntity.status(401).build();
        }
        Set<String> roles = jwtService.getRolesFromToken(token);
        return ResponseEntity.ok(roles);
    }

    @GetMapping("/subject")
    public ResponseEntity<String> getSubject(@RequestParam String token) {
        if (!jwtService.validateToken(token)) {
            return ResponseEntity.status(401).build();
        }
        String subject = jwtService.getUsernameFromToken(token);
        return ResponseEntity.ok(subject);
    }
}
