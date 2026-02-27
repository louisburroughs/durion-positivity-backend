package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.GoodsReceiptEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoodsReceiptRepository extends JpaRepository<GoodsReceiptEntity, UUID> {

    List<GoodsReceiptEntity> findByPoId(UUID poId);
}
