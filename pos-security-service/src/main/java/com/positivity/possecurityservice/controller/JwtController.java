package com.positivity.possecurityservice.controller;

import com.positivity.possecurityservice.service.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/jwt")
@RequiredArgsConstructor
public class JwtController {
    private final JwtService jwtService;

    @PostMapping("/generate")
    public ResponseEntity<Map<String, String>> generateToken(
            @RequestParam String subject,
            @RequestParam(required = false) Set<String> roles) {
        String token = jwtService.generateToken(subject, roles != null ? roles : Set.of());
        return ResponseEntity.ok(Map.of("token", token));
    }

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
}

