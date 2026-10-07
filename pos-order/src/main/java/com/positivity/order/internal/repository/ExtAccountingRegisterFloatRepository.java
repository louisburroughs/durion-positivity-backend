package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.ExtAccountingRegisterFloat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** pos-order's copy of accounting's register floats (#2512, ADR-0044 R3). */
public interface ExtAccountingRegisterFloatRepository extends JpaRepository<ExtAccountingRegisterFloat, UUID> {

    Optional<ExtAccountingRegisterFloat> findByRegisterId(String registerId);
}
