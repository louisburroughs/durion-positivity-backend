package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.InvoiceItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface InvoiceItemRepository extends JpaRepository<InvoiceItem, UUID> {
}
