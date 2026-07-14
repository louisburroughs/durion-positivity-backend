package com.positivity.peoplecontact.internal.repository;

import com.positivity.peoplecontact.internal.entity.UserPersonLink;
import com.positivity.peoplecontact.internal.enums.UserLinkStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPersonLinkRepository extends JpaRepository<UserPersonLink, UUID> {

    Optional<UserPersonLink> findByUsername(@NonNull String username);

    List<UserPersonLink> findByPerson_Id(@NonNull UUID personId);

    List<UserPersonLink> findByPerson_IdIn(@NonNull Collection<UUID> personIds);

    Optional<UserPersonLink> findByPerson_IdAndStatus(@NonNull UUID personId, @NonNull UserLinkStatus status);

    Optional<UserPersonLink> findFirstByPerson_IdAndStatusOrderByCreatedAtDesc(
            @NonNull UUID personId, @NonNull UserLinkStatus status);

    boolean existsByUsername(@NonNull String username);

    boolean existsByUsernameAndPerson_Id(@NonNull String username, @NonNull UUID personId);

    void deleteByUsername(@NonNull String username);
}
