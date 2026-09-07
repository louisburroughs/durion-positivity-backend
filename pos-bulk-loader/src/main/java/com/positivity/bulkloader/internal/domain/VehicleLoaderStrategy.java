package com.positivity.bulkloader.internal.domain;

import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

@Component
public class VehicleLoaderStrategy implements DomainLoaderStrategy<VehicleBulkRecord> {

    @Override
    public DomainType getDomainType() {
        return DomainType.VEHICLE;
    }

    @Override
    public VehicleBulkRecord mapRow(@NonNull Map<String, String> row) {
        VehicleBulkRecord vehicleBulkRecord = new VehicleBulkRecord();
        vehicleBulkRecord.setAccountId(row.get("accountId"));
        vehicleBulkRecord.setVin(row.get("vin"));
        vehicleBulkRecord.setUnitNumber(row.get("unitNumber"));
        vehicleBulkRecord.setDescription(row.get("description"));
        vehicleBulkRecord.setMake(row.get("make"));
        vehicleBulkRecord.setModel(row.get("model"));
        vehicleBulkRecord.setYear(row.get("year"));
        vehicleBulkRecord.setTrim(row.get("trim"));
        vehicleBulkRecord.setLicensePlate(row.get("licensePlate"));
        vehicleBulkRecord.setLicensePlateJurisdiction(row.get("licensePlateJurisdiction"));
        vehicleBulkRecord.setOwnerType(row.get("ownerType"));
        vehicleBulkRecord.setOwnerName(row.get("ownerName"));
        return vehicleBulkRecord;
    }

    /**
     * Turns {@code ownerType} + {@code ownerName} into the account id the vehicle ingest expects.
     *
     * <p>Rows that already carry an {@code accountId} are left alone, so a file that knows the id
     * still loads unchanged. The matching rule — exact name, and nothing at all when the name is
     * ambiguous — lives in {@link CustomerResolutions}, shared with the packs that name a fleet.
     */
    @Override
    @NonNull
    public VehicleBulkRecord resolve(@NonNull VehicleBulkRecord item, @NonNull ResolutionContext context) {
        if (LoaderValues.isPresent(item.getAccountId())
                || !LoaderValues.isPresent(item.getOwnerName())
                || !LoaderValues.isPresent(item.getOwnerType())) {
            return item;
        }
        CustomerResolutions.partyId(context, item.getOwnerType(), item.getOwnerName(), "Vehicle owner")
                .ifPresent(item::setAccountId);
        return item;
    }

    @Override
    public List<String> validate(@NonNull VehicleBulkRecord item) {
        List<String> errors = new ArrayList<>();
        if (item.getAccountId() == null || item.getAccountId().isBlank()) {
            // Names the resolvable alternative, because for a fixture-shaped file the operator
            // supplied ownerType/ownerName and the real fault is that they matched no party.
            errors.add("accountId is required (or an ownerType and ownerName that resolve to one)");
        } else {
            try {
                java.util.UUID.fromString(item.getAccountId());
            } catch (IllegalArgumentException _) {
                errors.add("accountId must be a valid UUID");
            }
        }
        if (item.getVin() == null || item.getVin().isBlank()) {
            errors.add("vin is required");
        } else if (item.getVin().trim().length() != 17) {
            errors.add("vin must be exactly 17 characters");
        }
        if (item.getUnitNumber() == null || item.getUnitNumber().isBlank()) {
            errors.add("unitNumber is required");
        }
        if (item.getDescription() == null || item.getDescription().isBlank()) {
            errors.add("description is required");
        }
        return errors;
    }
}
