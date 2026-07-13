package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.ExtWorkorderReplica;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtWorkorderReplicaRepository extends JpaRepository<ExtWorkorderReplica, UUID> {

    List<ExtWorkorderReplica> findByWorkorderNumberContainingIgnoreCase(String query, Pageable pageable);
}
