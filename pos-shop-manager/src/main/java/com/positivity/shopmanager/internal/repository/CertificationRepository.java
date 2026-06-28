package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.Certification;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CertificationRepository extends JpaRepository<Certification, UUID> {}
