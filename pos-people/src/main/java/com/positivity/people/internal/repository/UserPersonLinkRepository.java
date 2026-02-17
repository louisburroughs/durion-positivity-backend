package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.UserPersonLink;
import com.positivity.people.internal.enums.UserLinkStatus;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserPersonLinkRepository extends JpaRepository<UserPersonLink, UUID> {

    Optional<UserPersonLink> findByUserId(@NonNull UUID userId);

    List<UserPersonLink> findByPersonId(@NonNull UUID personId);

    Optional<UserPersonLink> findByPersonIdAndStatus(@NonNull UUID personId, @NonNull UserLinkStatus status);

    boolean existsByUserId(@NonNull UUID userId);

    boolean existsByUserIdAndPersonId(@NonNull UUID userId, @NonNull UUID personId);

    void deleteByUserId(@NonNull UUID userId);
}
