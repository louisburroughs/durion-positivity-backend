package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.ExtPersonReplica;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtPersonReplicaRepository extends JpaRepository<ExtPersonReplica, UUID> {

    @NonNull
    List<ExtPersonReplica> findByPersonIdIn(@NonNull Collection<UUID> personIds);

    @NonNull
    List<ExtPersonReplica> findByPrimaryEmailIgnoreCase(@NonNull String primaryEmail);

    @NonNull
    List<ExtPersonReplica> findByLastNameIgnoreCase(@NonNull String lastName);

    /** Candidate rows whose contact-point JSON contains the value (re-verified in the caller). */
    @NonNull
    List<ExtPersonReplica> findByContactPointsContaining(@NonNull String value);

    @NonNull
    List<ExtPersonReplica> findByFirstNameIgnoreCase(@NonNull String firstName);

    /** Directory search over names + flattened primary email (case-insensitive contains). */
    @NonNull
    @Query("""
                        SELECT p
                        FROM ExtPersonReplica p
                        WHERE lower(p.firstName) LIKE lower(concat('%', :q, '%'))
                           OR lower(p.lastName) LIKE lower(concat('%', :q, '%'))
                           OR lower(p.primaryEmail) LIKE lower(concat('%', :q, '%'))
                        """)
    List<ExtPersonReplica> search(@Param("q") @NonNull String q);
}
