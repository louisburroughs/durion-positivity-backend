package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ExtInvoice;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtInvoiceRepository extends JpaRepository<ExtInvoice, UUID> {}
