package com.positivity.possecurityservice.controller;

import com.positivity.possecurityservice.model.User;
import com.positivity.possecurityservice.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @PostMapping
    public ResponseEntity<User> createUser(@RequestBody Map<String, Object> payload) {
        String username = (String) payload.get("username");
        String password = (String) payload.get("password");
        Set<String> roles = Set.copyOf((List<String>) payload.get("roles"));
        User user = userService.createUser(username, password, roles);
        return ResponseEntity.ok(user);
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody Map<String, String> payload) {
        String username = payload.get("username");
        String password = payload.get("password");
        Optional<User> userOpt = userService.getUserByUsername(username);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }
        User user = userOpt.get();
        if (!userService.getPasswordEncoder().matches(password, user.getPassword())) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }
        Set<String> roles = user.getRoles().stream().map(r -> r.getName()).collect(java.util.stream.Collectors.toSet());
        String token = jwtService.generateToken(username, roles);
        return ResponseEntity.ok(Map.of("token", token));
    }

    @GetMapping
    public List<User> getAllUsers() {
        return userService.getAllUsers();
    }

    @GetMapping("/{username}")
    public ResponseEntity<User> getUser(@PathVariable String username) {
        Optional<User> user = userService.getUserByUsername(username);
        return user.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{username}/roles")
    public ResponseEntity<User> assignRoles(@PathVariable String username, @RequestBody Map<String, Object> payload) {
        Set<String> roles = Set.copyOf((List<String>) payload.get("roles"));
        User user = userService.assignRoles(username, roles);
        return ResponseEntity.ok(user);
    }
}

