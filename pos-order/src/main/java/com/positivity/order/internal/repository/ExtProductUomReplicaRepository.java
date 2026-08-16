package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.ExtProductUomReplica;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtProductUomReplicaRepository extends JpaRepository<ExtProductUomReplica, ExtProductUomReplica.Key> {

    Optional<ExtProductUomReplica> findByProductIdAndUomCode(UUID productId, String uomCode);

    @Modifying
    @Query("DELETE FROM ExtProductUomReplica u WHERE u.productId = :productId")
    void deleteByProductId(@Param("productId") UUID productId);
}
