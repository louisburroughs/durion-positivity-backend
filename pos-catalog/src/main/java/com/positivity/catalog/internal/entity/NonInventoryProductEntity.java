package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@Setter
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "non_inventory_product")
public class NonInventoryProductEntity implements CatalogItem {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    private String name;
    private String longDescription;
    private String shortDescription;

    @Override
    public String getLongDescription() {
        return this.longDescription;
    }

    @Override
    public void setId(UUID id) {
        this.id = id;
    }
}
