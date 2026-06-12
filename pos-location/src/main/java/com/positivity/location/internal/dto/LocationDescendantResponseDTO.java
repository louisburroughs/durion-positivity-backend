package com.positivity.location.internal.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Flat projection of a descendant location returned by the descendants
 * traversal endpoint.
 *
 * Issue: CAP-214 #655
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LocationDescendantResponseDTO {

    private UUID id;
    private String name;
    private String code;
    private String status;

    /** Immediate parent of this location within the traversed chain. */
    private UUID parentId;

    /** Distance from the requested root location (1 = direct child). */
    private int depth;
}
