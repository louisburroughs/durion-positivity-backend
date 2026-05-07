package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.RoleAssignmentRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("CustomUserDetailsServiceIT")
class CustomUserDetailsServiceIT {

    @Autowired
    private CustomUserDetailsService customUserDetailsService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RoleAssignmentRepository roleAssignmentRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("loadUserByUsername resolves effective role assignments outside repository transaction")
    void loadUserByUsername_resolvesEffectiveAssignmentRoleNamesWithoutLazyInitFailure() {
        String username = "rbac-it-" + UUID.randomUUID();

        User user = new User();
        user.setUsername(username);
        user.setPassword("{noop}password");
        User savedUser = userRepository.saveAndFlush(user);

        Role role = new Role();
        role.setName("SECURITY_ADMIN_IT_" + UUID.randomUUID().toString().replace("-", ""));
        role.setDescription("Integration-test role");
        role.setCreatedAt(Instant.now());
        role.setCreatedBy("test");
        Role savedRole = roleRepository.saveAndFlush(role);

        RoleAssignment assignment = new RoleAssignment();
        assignment.setUser(savedUser);
        assignment.setRole(savedRole);
        assignment.setCreatedBy("test");
        assignment.setEffectiveStartDate(LocalDateTime.now().minusDays(1));
        roleAssignmentRepository.saveAndFlush(assignment);

        entityManager.clear();

        UserDetails principal = customUserDetailsService.loadUserByUsername(username);

        assertThat(principal.getAuthorities()).extracting("authority").contains("ROLE_" + savedRole.getName());
    }
}
