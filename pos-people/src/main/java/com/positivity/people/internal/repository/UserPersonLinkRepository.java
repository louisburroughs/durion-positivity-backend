package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.UserPersonLink;
import com.positivity.people.internal.enums.UserLinkStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPersonLinkRepository extends JpaRepository<UserPersonLink, UUID> {

    Optional<UserPersonLink> findByUserId(@NonNull UUID userId);

    List<UserPersonLink> findByPerson_Id(@NonNull UUID personId);

    Optional<UserPersonLink> findByPerson_IdAndStatus(@NonNull UUID personId, @NonNull UserLinkStatus status);

    Optional<UserPersonLink> findFirstByPerson_IdAndStatusOrderByCreatedAtDesc(
            @NonNull UUID personId, @NonNull UserLinkStatus status);

    boolean existsByUserId(@NonNull UUID userId);

    boolean existsByUserIdAndPerson_Id(@NonNull UUID userId, @NonNull UUID personId);

    void deleteByUserId(@NonNull UUID userId);
}
