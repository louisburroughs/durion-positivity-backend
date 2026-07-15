package com.positivity.warranty.internal.repository;

import com.positivity.warranty.internal.entity.WarrantyRegistration;
import com.positivity.warranty.internal.enums.RegistrationStatus;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarrantyRegistrationRepository extends JpaRepository<WarrantyRegistration, UUID> {

    @NonNull
    List<WarrantyRegistration> findByCustomerId(@NonNull UUID customerId);

    @NonNull
    List<WarrantyRegistration> findByVehicleId(@NonNull UUID vehicleId);

    @NonNull
    List<WarrantyRegistration> findByCustomerIdAndVehicleId(@NonNull UUID customerId, @NonNull UUID vehicleId);

    @NonNull
    List<WarrantyRegistration> findByCustomerIdAndStatus(@NonNull UUID customerId, @NonNull RegistrationStatus status);

    @NonNull
    List<WarrantyRegistration> findByPolicyId(@NonNull UUID policyId);
}
