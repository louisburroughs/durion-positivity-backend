package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.location.internal.enums.AllowNewProductPolicy;
import com.positivity.location.internal.enums.StorageCategory;
import com.positivity.location.internal.enums.StorageLocationStatus;
import com.positivity.location.internal.enums.StorageLocationType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Data;

/** A single storage location within a bulk ingest request. */
@Data
@Schema(description = "One storage location to create at a site")
public class StorageLocationBulkIngestRecord {

    @Schema(
            description = "Site the location belongs to. Defaults to the request's locationId when omitted,"
                    + " so a single-site file need not repeat it.",
            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10",
            requiredMode = NOT_REQUIRED)
    private UUID siteId;

    @Schema(
            description = "Name of the storage location, unique within its site",
            example = "Bin A-01",
            requiredMode = REQUIRED)
    @NotBlank
    private String name;

    @Schema(description = "Physical topology of the location", example = "BIN", requiredMode = REQUIRED)
    @NotNull
    private StorageLocationType type;

    @Schema(
            description = "Name of the parent location within the same site. Resolved against locations already"
                    + " at the site and those created earlier in the same batch, so a file may create a shelf"
                    + " and its bins in one call as long as the shelf comes first.",
            example = "Parts Shelf A",
            requiredMode = NOT_REQUIRED)
    private String parentName;

    @Schema(
            description = "What the location is fit to hold, which is independent of its physical type:"
                    + " a tire rack and a bulk pallet area are both shelves or floors.",
            example = "SMALL_PARTS_BIN",
            requiredMode = NOT_REQUIRED)
    private StorageCategory storageCategoryCode;

    // Boxed on purpose: Jackson's FAIL_ON_NULL_FOR_PRIMITIVES would reject every payload that
    // omits the field, and most rows have no reason to mention containment at all.
    @Schema(description = "Whether the location has spill containment", example = "false", requiredMode = NOT_REQUIRED)
    private Boolean hazardContainment;

    @Schema(description = "Mixing policy for new products", example = "MIXED", requiredMode = NOT_REQUIRED)
    private AllowNewProductPolicy allowNewProduct;

    @Schema(
            description = "Maximum units the location holds. This is the cap only — the fill is real stock,"
                    + " never a declared number.",
            example = "500",
            requiredMode = NOT_REQUIRED)
    private Integer maxUnitCount;

    @Schema(
            description = "Status to apply. Creation always produces an ACTIVE location, so anything else is"
                    + " applied as a follow-up update.",
            example = "INACTIVE",
            requiredMode = NOT_REQUIRED)
    private StorageLocationStatus status;
}
