package com.positivity.tenant.internal.repository;

import com.positivity.tenant.internal.entity.AccountEntity;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {

    boolean existsByLegalNameIgnoreCase(@NonNull String legalName);

    @NonNull
    List<AccountEntity> findAllByOrderByLegalNameAsc();
}
