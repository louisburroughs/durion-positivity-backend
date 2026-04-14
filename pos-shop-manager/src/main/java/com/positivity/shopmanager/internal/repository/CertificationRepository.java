package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.Certification;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CertificationRepository extends JpaRepository<Certification, UUID> {}
