package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ExtProductUomReplica;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

public interface ExtProductUomReplicaRepository extends JpaRepository<ExtProductUomReplica, ExtProductUomReplica.Key> {

    Optional<ExtProductUomReplica> findByProductIdAndUomCode(@NonNull UUID productId, @NonNull String uomCode);

    @NonNull
    List<ExtProductUomReplica> findByProductId(@NonNull UUID productId);

    /** Batch read for {@code BaseUnitOfMeasureResolver.resolveAll} — one {@code IN} query for a page of rows. */
    @NonNull
    List<ExtProductUomReplica> findByProductIdIn(@NonNull Collection<UUID> productIds);

    @Modifying
    void deleteByProductId(@NonNull UUID productId);
}
