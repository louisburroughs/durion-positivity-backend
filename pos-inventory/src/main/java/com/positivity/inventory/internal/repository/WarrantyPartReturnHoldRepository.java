package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.WarrantyPartReturnHold;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarrantyPartReturnHoldRepository extends JpaRepository<WarrantyPartReturnHold, UUID> {}
