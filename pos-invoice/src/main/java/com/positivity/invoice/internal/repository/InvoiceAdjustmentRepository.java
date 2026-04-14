package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.InvoiceAdjustment;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceAdjustmentRepository extends JpaRepository<InvoiceAdjustment, UUID> {}
