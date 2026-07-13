package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.ExtJobTimeReplica;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtJobTimeReplicaRepository extends JpaRepository<ExtJobTimeReplica, UUID> {

    @NonNull
    List<ExtJobTimeReplica> findByEndAtUtcBetween(@NonNull Instant from, @NonNull Instant to);
}
