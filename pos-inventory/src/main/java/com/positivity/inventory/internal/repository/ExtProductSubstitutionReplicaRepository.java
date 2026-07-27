package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ExtProductSubstitutionReplica;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

public interface ExtProductSubstitutionReplicaRepository
        extends JpaRepository<ExtProductSubstitutionReplica, ExtProductSubstitutionReplica.Key> {

    @NonNull
    List<ExtProductSubstitutionReplica> findByProductId(@NonNull UUID productId);

    @Modifying
    void deleteByProductId(@NonNull UUID productId);
}
