package com.positivity.securityservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for {@link User} entity account-state field persistence —
 * AUTH-002.
 *
 * <p>
 * T15 verifies that account-state columns added by
 * {@code V6__add_account_state_columns.sql}
 * are correctly mapped in the JPA entity and that values written to H2 are read
 * back
 * accurately after flushing and clearing the persistence context. These tests
 * exercise
 * only the entity/repository layer and are expected to PASS immediately.
 *
 * <p>
 * Uses {@code @SpringBootTest(webEnvironment = NONE)} with the {@code test}
 * profile
 * to match the pattern established in {@code PermissionBitIndexTest}.
 *
 * Issue: AUTH-002
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
@DisplayName("UserEntityAccountStateIT — AUTH-002")
class UserEntityAccountStateIT {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    // =========================================================
    // T15 — account-state fields round-trip through JPA / H2
    // Expected: PASS (entity + migration are correct)
    // =========================================================

    @Test
    @DisplayName("T15: User entity persists enabled=false and accountNonLocked=false and retrieves them correctly")
    void t15_accountStateFields_roundTripThroughRepository() {
        User user = new User();
        // Unique username per test run to avoid cross-test contamination
        user.setUsername("state-it-" + UUID.randomUUID());
        user.setPassword("{noop}password");
        user.setEnabled(false);
        user.setAccountNonLocked(false);

        User saved = userRepository.saveAndFlush(user);

        // Clear the first-level cache to force a genuine SQL SELECT on the next read
        entityManager.clear();

        User fetched = userRepository
                .findById(saved.getId())
                .orElseThrow(() -> new AssertionError("User was not found after saveAndFlush — ID: " + saved.getId()));

        assertThat(fetched.isEnabled())
                .as("enabled must be persisted and retrieved as false")
                .isFalse();
        assertThat(fetched.isAccountNonLocked())
                .as("accountNonLocked must be persisted and retrieved as false")
                .isFalse();

        // Verify the remaining flags retain their entity defaults after a full
        // round-trip
        assertThat(fetched.isAccountNonExpired())
                .as("accountNonExpired default (true) must survive round-trip")
                .isTrue();
        assertThat(fetched.isCredentialsNonExpired())
                .as("credentialsNonExpired default (true) must survive round-trip")
                .isTrue();
        assertThat(fetched.getFailedLoginAttempts())
                .as("failedLoginAttempts default (0) must survive round-trip")
                .isZero();
    }

    // =========================================================
    // T16 — nullable audit-trail fields round-trip through JPA / H2
    // Expected: PASS (entity mapping + migration are correct)
    // =========================================================

    @Test
    @DisplayName("T16: User entity persists all nullable audit-trail fields correctly")
    void t16_auditTrailFields_roundTripThroughRepository() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        User user = new User();
        user.setUsername("audit-it-" + UUID.randomUUID());
        user.setPassword("{noop}password");
        user.setLastFailedLoginAt(now);
        user.setLastSuccessfulLoginAt(now);
        user.setLockedAt(now);
        user.setLockedUntil(now.plusSeconds(3600));
        user.setDisabledAt(now);
        user.setDisabledBy("admin@example.com");
        user.setAccountExpiresAt(now.plusSeconds(7200));
        user.setCredentialsExpireAt(now.plusSeconds(86400));
        user.setLastLoginIp("192.168.1.100");
        user.setLastLoginUserAgent("Mozilla/5.0 (Test)");

        User saved = userRepository.saveAndFlush(user);
        entityManager.clear();

        User fetched = userRepository
                .findById(saved.getId())
                .orElseThrow(() -> new AssertionError("User not found after saveAndFlush — ID: " + saved.getId()));

        assertThat(fetched.getLastFailedLoginAt()).isNotNull();
        assertThat(fetched.getLastSuccessfulLoginAt()).isNotNull();
        assertThat(fetched.getLockedAt()).isNotNull();
        assertThat(fetched.getLockedUntil()).isAfter(now);
        assertThat(fetched.getDisabledAt()).isNotNull();
        assertThat(fetched.getDisabledBy()).isEqualTo("admin@example.com");
        assertThat(fetched.getAccountExpiresAt()).isAfter(now);
        assertThat(fetched.getCredentialsExpireAt()).isAfter(now);
        assertThat(fetched.getLastLoginIp()).isEqualTo("192.168.1.100");
        assertThat(fetched.getLastLoginUserAgent()).isEqualTo("Mozilla/5.0 (Test)");
        // Spring Data auditing must populate these on first save
        assertThat(fetched.getCreatedAt()).isNotNull();
        assertThat(fetched.getUpdatedAt()).isNotNull();
    }
}
