package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.ExtEmployeeReplica;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtEmployeeReplicaRepository extends JpaRepository<ExtEmployeeReplica, UUID> {

    @NonNull
    Optional<ExtEmployeeReplica> findFirstByEmployeeNumberIgnoreCase(@NonNull String employeeNumber);
}
