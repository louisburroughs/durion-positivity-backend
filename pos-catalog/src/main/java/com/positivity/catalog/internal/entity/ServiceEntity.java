package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.Data;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "service")
public class ServiceEntity implements CatalogItem {
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
