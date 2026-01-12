package com.positivity.securityservice.config;

import com.positivity.securityservice.model.Role;
import com.positivity.securityservice.repository.RoleRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class RoleInitializer {
    private final RoleRepository roleRepository;

    @PostConstruct
    public void initRoles() {
        List<String> roles = List.of("ADMIN", "GENERAL_MANAGER", "MANAGER", "CUSTOMER");
        for (String roleName : roles) {
            if (!roleRepository.existsByName(roleName)) {
                Role role = new Role();
                role.setName(roleName);
                role.setDescription("Default " + roleName + " role");
                role.setCreatedBy("system");
                role.setCreatedAt(Instant.now());
                roleRepository.save(role);
                log.info("Initialized default role: {}", roleName);
            }
        }
    }
}

