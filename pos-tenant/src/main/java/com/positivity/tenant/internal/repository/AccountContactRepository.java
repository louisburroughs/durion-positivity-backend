package com.positivity.tenant.internal.repository;

import com.positivity.tenant.internal.entity.AccountContactEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountContactRepository extends JpaRepository<AccountContactEntity, UUID> {

    @NonNull
    List<AccountContactEntity> findByAccountIdOrderByCreatedAtAsc(@NonNull UUID accountId);

    Optional<AccountContactEntity> findByIdAndAccountId(@NonNull UUID id, @NonNull UUID accountId);
}
