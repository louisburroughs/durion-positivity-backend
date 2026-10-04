package com.positivity.vehiclefitment.internal.repository;

import com.positivity.vehiclefitment.internal.entity.Make;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MakeRepository extends JpaRepository<Make, UUID> {
    /** Every make linked to the manufacturer through {@code make_manufacturer}. */
    List<Make> findByManufacturersId(UUID manufacturerId);

    /** The manufacturer's linked makes with this name; more than one only when a vPIC and a local row share it. */
    List<Make> findByManufacturersIdAndNameIgnoreCase(UUID manufacturerId, String name);

    List<Make> findAllByNameIgnoreCase(String name);
}
