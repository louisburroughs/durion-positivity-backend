package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.InvoiceAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface InvoiceAdjustmentRepository extends JpaRepository<InvoiceAdjustment, UUID> {
}
