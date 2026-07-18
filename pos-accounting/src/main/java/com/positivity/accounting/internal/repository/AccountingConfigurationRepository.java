package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.AccountingConfiguration;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for the org-level accounting configuration key/value store
 * (story B2, issue #944).
 */
@Repository
public interface AccountingConfigurationRepository extends JpaRepository<AccountingConfiguration, UUID> {

    Optional<AccountingConfiguration> findByConfigKey(@NonNull String configKey);
}
