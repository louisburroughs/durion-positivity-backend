package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.ExtProductCode;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtProductCodeRepository extends JpaRepository<ExtProductCode, UUID> {}
