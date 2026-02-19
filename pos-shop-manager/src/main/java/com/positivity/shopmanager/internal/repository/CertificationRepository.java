package com.positivity.shopmanager.internal.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.positivity.shopmanager.internal.entity.Certification;

@Repository
public interface CertificationRepository extends JpaRepository<Certification, UUID> {
}

